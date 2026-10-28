package com.focuslock.app

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Serviço de bloqueio baseado em UsageStatsManager (Acesso ao Uso).
 *
 * Ao contrário do AccessibilityService, o UsageStatsManager NÃO possui capacidade de ler textos
 * da tela, senhas ou chaves PIX, nem interagir/clicar em outros aplicativos. Isso evita falsos
 * positivos em sistemas antifraude de aplicativos bancários (como Nubank, Itaú, Bradesco, etc.).
 */
class AppBlockerService : Service() {

    private var lastBlockedTime = 0L
    private var lastStatRecordedTime = 0L
    private var currentForegroundPackage = ""
    private var currentForegroundClass = ""
    private var wasBlockingActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var powerManager: PowerManager? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            var nextDelay = 1500L
            try {
                val isScreenOn = powerManager?.isInteractive ?: true
                if (isScreenOn) {
                    val prefs = (application as? FocusLockApp)?.prefs
                    val isActive = prefs?.isBlockingCurrentlyActive() == true
                    if (isActive) {
                        nextDelay = 450L
                        val lookbackMs = if (!wasBlockingActive) 120_000L else 5_000L
                        wasBlockingActive = true
                        checkCurrentWindowAndBlock(lookbackMs)
                    } else {
                        wasBlockingActive = false
                    }
                } else {
                    nextDelay = 2000L
                }
            } catch (e: Exception) {
                Log.e("AppBlockerService", "Erro no monitoramento de foco", e)
            }
            handler.postDelayed(this, nextDelay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        startForegroundSafely()
        handler.removeCallbacks(watchdogRunnable)
        handler.post(watchdogRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        return START_STICKY
    }

    private fun startForegroundSafely() {
        try {
            val channelId = CHANNEL_ID
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Monitoramento de Foco",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantém o bloqueio de distrações ativo em segundo plano"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_focus_shield)
                .setContentTitle("FocusLock Ativo")
                .setContentText("Proteção de foco pronta (Modo Compatível com Bancos)")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(contentIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("AppBlockerService", "Falha ao iniciar notificação foreground", e)
        }
    }

    private fun checkCurrentWindowAndBlock(lookbackMs: Long) {
        if (!isServiceEnabled(this)) return
        val (activePkg, activeClass) = queryForegroundPackageAndClass(lookbackMs)
        if (activePkg.isNotBlank()) {
            evaluateAndBlockPackage(activePkg, activeClass)
        }
    }

    private fun queryForegroundPackageAndClass(lookbackMs: Long): Pair<String, String> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return currentForegroundPackage to currentForegroundClass

        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - lookbackMs, now)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestClass: String? = null
        var latestTime = 0L

        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                if (event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPkg = event.packageName
                    latestClass = event.className ?: ""
                }
            }
        }

        if (!latestPkg.isNullOrBlank()) {
            currentForegroundPackage = latestPkg
            currentForegroundClass = latestClass ?: ""
        } else if (currentForegroundPackage.isBlank()) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
            val mostRecent = stats?.maxByOrNull { it.lastTimeUsed }
            if (mostRecent != null && !mostRecent.packageName.isNullOrBlank()) {
                currentForegroundPackage = mostRecent.packageName
            }
        }

        return currentForegroundPackage to currentForegroundClass
    }

    private fun evaluateAndBlockPackage(packageName: String, className: String) {
        if (packageName.isBlank()) return

        // Ignora o próprio FocusLock
        if (packageName == this.packageName) {
            return
        }

        val prefs = (application as? FocusLockApp)?.prefs ?: BlockPreferences(applicationContext)

        if (!prefs.isBlockingCurrentlyActive() || LockActivity.isShowing) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < 400L) {
            return
        }

        // Proteção Anti-Burla (Strict Mode): impede desinstalar ou forçar parada durante o bloqueio
        if (prefs.isAntiTamperEnabled && isSettingsTamperAttempt(packageName, className)) {
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            launchLockScreen("Proteção Anti-Burla (Configurações)")
            return
        }

        if (prefs.shouldBlockNow(packageName)) {
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            Log.d("AppBlockerService", "Bloqueando pacote restrito: $packageName")
            launchLockScreen(packageName)
        }
    }

    private fun isSettingsTamperAttempt(packageName: String, className: String): Boolean {
        // Só ativa a proteção anti-burla se ambas as permissões já estiverem concedidas
        if (!isServiceEnabled(this) || !Settings.canDrawOverlays(this)) {
            return false
        }

        val lowerPkg = packageName.lowercase()
        val lowerClass = className.lowercase()

        if (lowerPkg.contains("packageinstaller") && lowerClass.contains("uninstall")) {
            return true
        }

        val isSettings = packageName == "com.android.settings" || lowerPkg.contains("securitycenter")
        if (!isSettings) return false

        val tamperClasses = listOf(
            "installedappdetails",
            "applicationdetails",
            "appmanage",
            "usageaccess",
            "drawoverlay",
            "uninstaller"
        )
        return tamperClasses.any { lowerClass.contains(it) }
    }

    private fun launchLockScreen(blockedPkgOrReason: String) {
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra("BLOCKED_PACKAGE", blockedPkgOrReason)
        }
        startActivity(lockIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        isRunning = false
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "focus_lock_monitor_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: AppBlockerService? = null
            private set

        fun onNavigatedHome() {
            instance?.let {
                it.currentForegroundPackage = ""
                it.currentForegroundClass = ""
                it.lastBlockedTime = System.currentTimeMillis() + 600L
            }
        }

        fun isServiceEnabled(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }

        fun ensureRunning(context: Context) {
            if (!isServiceEnabled(context)) return
            try {
                val intent = Intent(context, AppBlockerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("AppBlockerService", "Erro ao iniciar AppBlockerService", e)
            }
        }
    }
}
