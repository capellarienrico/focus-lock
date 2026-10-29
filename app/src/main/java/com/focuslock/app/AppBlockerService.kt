package com.focuslock.app

import android.Manifest
import android.app.ActivityOptions
import android.app.AlarmManager
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Motor central do FocusLock Super App:
 * - Bloqueio em tempo real baseado em UsageStatsManager + WindowManager Overlay (100% seguro p/ bancos)
 * - Sessões Rápidas de Foco / Pomodoro com auto-conclusão
 * - Limite Diário de Tempo de Tela por App
 * - Pílula Flutuante Minimalista ("🔕 X notificações filtradas") sincronizada com a Tela Inicial
 */
class AppBlockerService : Service() {

    private var lastBlockedTime = 0L
    private var lastStatRecordedTime = 0L
    private var ignoreEventsBeforeTimestamp = 0L
    private var currentForegroundPackage = ""
    private var currentForegroundClass = ""
    private var wasBlockingActive = false
    private var wasTimedFocusActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val usageRefreshExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var cachedTodayUsageMap: Map<String, Long> = emptyMap()
    @Volatile
    private var lastUsageRefreshMillis = 0L
    @Volatile
    private var isRefreshingUsage = false

    private var powerManager: PowerManager? = null
    private var lockOverlay: LockOverlayController? = null
    private var floatingPill: FloatingFilterPillController? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            var nextDelay = 1500L
            try {
                val isScreenOn = powerManager?.isInteractive ?: true
                if (isScreenOn) {
                    val prefs = getPrefs()

                    // Verifica conclusão natural do Timer Pomodoro / Sessão Rápida
                    val isTimerNow = prefs.isTimedFocusActive()
                    if (wasTimedFocusActive && !isTimerNow && prefs.timedFocusUntilMillis > 0L) {
                        prefs.stopTimedFocus()
                        postTimedFocusCompletedNotification()
                        FocusNotificationListenerService.syncSummaryNotification(this@AppBlockerService)
                    }
                    wasTimedFocusActive = isTimerNow

                    val isBlocking = prefs.isBlockingCurrentlyActive()
                    val isTimeLimitMonitoring = prefs.isDailyTimeLimitEnabled &&
                            prefs.getTimeLimitedPackages().isNotEmpty() &&
                            !prefs.isTimeLimitSnoozed()
                    val isPillHomeTracking = prefs.isNotificationFilterEnabled &&
                            prefs.floatingPillMode != BlockPreferences.PILL_MODE_OFF &&
                            NotificationFilterStore.getInstance(this@AppBlockerService).getActiveCount() > 0

                    if (isBlocking) {
                        nextDelay = 350L
                        val lookbackMs = if (!wasBlockingActive) 7_200_000L else 60_000L
                        wasBlockingActive = true
                        checkCurrentWindowAndEvaluate(lookbackMs, isBlocking = true, isTimeLimitMonitoring = isTimeLimitMonitoring)
                    } else {
                        if (wasBlockingActive) {
                            lockOverlay?.dismiss()
                        }
                        wasBlockingActive = false

                        if (isTimeLimitMonitoring || isPillHomeTracking) {
                            nextDelay = if (isTimeLimitMonitoring) 1000L else 1400L
                            checkCurrentWindowAndEvaluate(60_000L, isBlocking = false, isTimeLimitMonitoring = isTimeLimitMonitoring)
                        } else {
                            updateFloatingPillForCurrentState()
                        }
                    }
                } else {
                    nextDelay = 2200L
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
        lockOverlay = LockOverlayController(
            serviceContext = this,
            prefsProvider = { getPrefs() },
            onOverlayVisibleCallback = { blockedReason ->
                launchLockActivitySafely(blockedReason)
            }
        )
        floatingPill = FloatingFilterPillController(
            serviceContext = this,
            prefsProvider = { getPrefs() }
        )
        wasTimedFocusActive = getPrefs().isTimedFocusActive()
        startForegroundSafely()
        restartWatchdogNow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        isRunning = true
        startForegroundSafely()
        restartWatchdogNow()
        return START_STICKY
    }

    private fun restartWatchdogNow() {
        handler.removeCallbacks(watchdogRunnable)
        handler.post(watchdogRunnable)
    }

    private fun getPrefs(): BlockPreferences {
        return (application as? FocusLockApp)?.prefs ?: BlockPreferences(applicationContext)
    }

    private fun isLockUiShowing(): Boolean {
        return LockActivity.isShowing || (lockOverlay?.isShowing == true)
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
            val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_focus_shield)
                .setContentTitle("FocusLock Super App Ativo")
                .setContentText("Proteção de foco & bem-estar prontos (Compatível com Bancos)")
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

    private fun postTimedFocusCompletedNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alertChannelId = "focus_lock_timer_done_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    alertChannelId,
                    "Conclusão de Sessão de Foco",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                this,
                1009,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, alertChannelId)
                .setSmallIcon(R.drawable.ic_focus_shield)
                .setContentTitle("✅ Sessão de Foco Concluída!")
                .setContentText("Parabéns pela disciplina! Seus aplicativos foram liberados.")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(1009, notif)
        } catch (_: Exception) {
        }
    }

    private fun checkCurrentWindowAndEvaluate(
        lookbackMs: Long,
        isBlocking: Boolean,
        isTimeLimitMonitoring: Boolean
    ) {
        if (!isServiceEnabled(this)) {
            updateFloatingPillForCurrentState()
            return
        }

        val (activePkg, activeClass) = queryForegroundPackageAndClass(lookbackMs)
        updateFloatingPillForCurrentState(activePkg)

        if (activePkg.isBlank()) return

        if (isBlocking) {
            evaluateAndBlockPackage(activePkg, activeClass)
        }

        if (isTimeLimitMonitoring && !isLockUiShowing()) {
            evaluateDailyTimeLimit(activePkg)
        }
    }

    private fun evaluateDailyTimeLimit(packageName: String) {
        if (packageName.isBlank() || packageName == this.packageName) return
        val prefs = getPrefs()
        if (!prefs.isDailyTimeLimitEnabled || prefs.isTimeLimitSnoozed()) return
        if (prefs.isAlwaysAllowed(packageName)) return

        val limitedSet = prefs.getTimeLimitedPackages()
        if (!limitedSet.contains(packageName)) return

        val now = System.currentTimeMillis()
        if (now - lastUsageRefreshMillis > 15_000L && !isRefreshingUsage) {
            isRefreshingUsage = true
            usageRefreshExecutor.execute {
                try {
                    cachedTodayUsageMap = AppMetadataHelper.queryTodayUsageMap(applicationContext)
                    lastUsageRefreshMillis = System.currentTimeMillis()
                } finally {
                    isRefreshingUsage = false
                }
            }
        }

        val usedTodayMs = cachedTodayUsageMap[packageName] ?: return
        val limitMs = prefs.dailyTimeLimitMinutes * 60_000L
        if (usedTodayMs >= limitMs) {
            if (now < lastBlockedTime || now - lastBlockedTime < 350L) return
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            showBlockScreen("TIME_LIMIT:$packageName")
        }
    }

    private fun updateFloatingPillForCurrentState(activePkg: String = currentForegroundPackage) {
        val pill = floatingPill ?: return
        if (isLockUiShowing() || FilteredNotificationsActivity.isResumed) {
            pill.hide()
            return
        }
        val count = NotificationFilterStore.getInstance(this).getActiveCount()
        val prefs = getPrefs()
        val isOnHomeOrMain = MainActivity.isResumed || prefs.isHomeLauncherPackage(activePkg)
        pill.updateVisibilityAndCount(count, isOnHomeOrMain)
    }

    private fun queryForegroundPackageAndClass(lookbackMs: Long): Pair<String, String> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return currentForegroundPackage to currentForegroundClass

        val now = System.currentTimeMillis()

        if (ignoreEventsBeforeTimestamp > 0L && now - ignoreEventsBeforeTimestamp > 1600L) {
            ignoreEventsBeforeTimestamp = 0L
        }

        val startQueryTime = maxOf(now - lookbackMs, ignoreEventsBeforeTimestamp)
        val events = usm.queryEvents(startQueryTime, now)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestClass: String? = null
        var latestTime = 0L

        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                if (event.timeStamp >= ignoreEventsBeforeTimestamp && event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPkg = event.packageName
                    latestClass = event.className ?: ""
                }
            }
        }

        if (!latestPkg.isNullOrBlank()) {
            currentForegroundPackage = latestPkg
            currentForegroundClass = latestClass ?: ""
            if (ignoreEventsBeforeTimestamp > 0L && latestPkg != this.packageName) {
                ignoreEventsBeforeTimestamp = 0L
            }
        } else if (ignoreEventsBeforeTimestamp == 0L) {
            val isSelfActuallyInForeground = MainActivity.isResumed ||
                    LockActivity.isShowing ||
                    FilteredNotificationsActivity.isResumed
            if (currentForegroundPackage.isBlank() ||
                (currentForegroundPackage == this.packageName && !isSelfActuallyInForeground)
            ) {
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 14_400_000L,
                    now
                )
                val mostRecent = stats
                    ?.filter { !it.packageName.isNullOrBlank() && (isSelfActuallyInForeground || it.packageName != this.packageName) }
                    ?.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null) {
                    currentForegroundPackage = mostRecent.packageName
                    currentForegroundClass = ""
                }
            }
        }

        return currentForegroundPackage to currentForegroundClass
    }

    private fun evaluateAndBlockPackage(packageName: String, className: String) {
        if (packageName.isBlank()) return
        if (packageName == this.packageName) return

        val prefs = getPrefs()
        if (!prefs.isBlockingCurrentlyActive() || isLockUiShowing()) {
            return
        }

        val now = System.currentTimeMillis()
        if (now < lastBlockedTime || now - lastBlockedTime < 350L) {
            return
        }

        if (prefs.isAntiTamperEnabled && isSettingsTamperAttempt(packageName, className)) {
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            showBlockScreen("Proteção Anti-Burla (Configurações)")
            return
        }

        if (prefs.shouldBlockNow(packageName)) {
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            Log.d("AppBlockerService", "Bloqueando pacote restrito: $packageName")
            showBlockScreen(packageName)
        }
    }

    private fun isSettingsTamperAttempt(packageName: String, className: String): Boolean {
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

    private fun showBlockScreen(blockedPkgOrReason: String) {
        floatingPill?.hide()
        val overlayShown = lockOverlay?.show(blockedPkgOrReason) == true
        if (!overlayShown) {
            launchLockActivitySafely(blockedPkgOrReason)
        }
    }

    private fun launchLockActivitySafely(blockedPkgOrReason: String) {
        if (LockActivity.isShowing) return
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra("BLOCKED_PACKAGE", blockedPkgOrReason)
        }

        try {
            val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getActivity(this, 2001, lockIntent, piFlags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                pi.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                pi.send()
            }
        } catch (_: Exception) {
            try {
                startActivity(lockIntent)
            } catch (e: Exception) {
                Log.w("AppBlockerService", "LockActivity bloqueada por BAL; mantendo LockOverlay ativo", e)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestartAlarm()
    }

    private fun scheduleRestartAlarm() {
        try {
            val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
                action = BootReceiver.ACTION_RESTART_SERVICE
            }
            val pi = PendingIntent.getBroadcast(
                applicationContext,
                3001,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000L,
                pi
            )
        } catch (e: Exception) {
            Log.w("AppBlockerService", "Não foi possível agendar reinício automático", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        usageRefreshExecutor.shutdownNow()
        lockOverlay?.dismiss()
        lockOverlay = null
        floatingPill?.hide()
        floatingPill = null
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

        fun onLockActivityResumed() {
            instance?.handler?.post {
                if (LockActivity.isShowing) {
                    instance?.lockOverlay?.dismiss()
                    instance?.floatingPill?.hide()
                }
            }
        }

        fun dismissOverlay() {
            instance?.handler?.post {
                instance?.lockOverlay?.dismiss()
            }
        }

        fun refreshFloatingFilterPill() {
            instance?.let { svc ->
                svc.handler.post {
                    svc.updateFloatingPillForCurrentState()
                }
            }
        }

        fun onUnlocked() {
            instance?.let { svc ->
                svc.wasBlockingActive = false
                svc.wasTimedFocusActive = false
                svc.handler.post {
                    svc.lockOverlay?.dismiss()
                    svc.updateFloatingPillForCurrentState()
                }
            }
        }

        fun notifyStateChanged() {
            instance?.let { svc ->
                svc.wasBlockingActive = false
                svc.lastUsageRefreshMillis = 0L
                svc.wasTimedFocusActive = svc.getPrefs().isTimedFocusActive()
                svc.handler.post {
                    svc.restartWatchdogNow()
                }
            }
        }

        fun onNavigatedHome() {
            instance?.let {
                val now = System.currentTimeMillis()
                it.currentForegroundPackage = ""
                it.currentForegroundClass = ""
                it.ignoreEventsBeforeTimestamp = now - 30L
                it.lastBlockedTime = now + 700L
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
                if (mode == AppOpsManager.MODE_DEFAULT) {
                    context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                            PackageManager.PERMISSION_GRANTED
                } else {
                    mode == AppOpsManager.MODE_ALLOWED
                }
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
