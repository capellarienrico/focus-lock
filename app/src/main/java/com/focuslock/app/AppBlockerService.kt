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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Motor central do FocusLock Super App (v3.3 Ultra-Low Battery Architecture):
 * - 0% de uso de CPU / 0 wakeups quando a tela está apagada (orientado a eventos ACTION_SCREEN_OFF / ON)
 * - Cursor incremental de UsageEvents (lê apenas o delta de ~450ms por ciclo em vez de 60s via Binder IPC)
 * - Hibernação inteligente alinhada à virada do minuto quando nenhum bloqueio está ativo
 * - 100% seguro com aplicativos bancários (sem AccessibilityService)
 */
class AppBlockerService : Service() {

    private var lastBlockedTime = 0L
    private var lastStatRecordedTime = 0L
    private var minEventTimestamp = 0L
    private var lastQueriedEventTime = 0L
    private var currentForegroundPackage = ""
    private var currentForegroundClass = ""
    private var wasSelfInForeground = false
    private var wasBlockingActive = false
    private var wasTimedFocusActive = false
    private var isScreenInteractive = true
    private var isScreenReceiverRegistered = false
    private var lastForegroundNotifBlockingState: Boolean? = null

    private var cachedUsagePermAllowed = true
    private var lastUsagePermCheckMillis = 0L

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

    private val timerCompletionRunnable = Runnable {
        checkTimedFocusCompletion(getPrefs())
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    // Pausa completamente o loop de monitoramento enquanto a tela está desligada (0% CPU)
                    handler.removeCallbacks(watchdogRunnable)
                    floatingPill?.hide()

                    // Se houver um Timer Pomodoro rodando, agenda um único disparo exato para a conclusão
                    val prefs = getPrefs()
                    handler.removeCallbacks(timerCompletionRunnable)
                    if (prefs.isTimedFocusActive()) {
                        val rem = prefs.getTimedFocusRemainingMillis()
                        handler.postDelayed(timerCompletionRunnable, (rem + 300L).coerceAtLeast(500L))
                    }
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    handler.removeCallbacks(timerCompletionRunnable)
                    // Força janela inicial curta (15s) ao reacender a tela para identificar o app aberto
                    lastQueriedEventTime = 0L
                    restartWatchdogNow()
                }
            }
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            // Se a tela está desligada, não reagenda o loop (aguarda ACTION_SCREEN_ON)
            val screenOnNow = isScreenInteractive && (powerManager?.isInteractive ?: true)
            if (!screenOnNow) {
                isScreenInteractive = false
                floatingPill?.hide()
                return
            }

            var nextDelay = 15_000L
            try {
                val prefs = getPrefs()
                checkTimedFocusCompletion(prefs)

                val isBlocking = prefs.isBlockingCurrentlyActive()
                syncForegroundNotificationIfChanged(isBlocking)

                val isTimeLimitMonitoring = prefs.isDailyTimeLimitEnabled &&
                        prefs.getTimeLimitedPackages().isNotEmpty() &&
                        !prefs.isTimeLimitSnoozed()

                // PILL_MODE_ALWAYS fica visível em qualquer app e não exige polling do UsageStatsManager!
                // Apenas PILL_MODE_HOME_ONLY precisa saber se o usuário está na Tela Inicial.
                val isPillHomeOnlyTracking = prefs.isNotificationFilterEnabled &&
                        prefs.floatingPillMode == BlockPreferences.PILL_MODE_HOME_ONLY &&
                        NotificationFilterStore.getInstance(this@AppBlockerService).getActiveCount() > 0

                if (isBlocking) {
                    nextDelay = 400L
                    val initialLookback = if (!wasBlockingActive) 1_800_000L else 15_000L
                    if (!wasBlockingActive) {
                        wasBlockingActive = true
                        FocusQuickTileService.requestListeningStateSafe(this@AppBlockerService)
                    }
                    checkCurrentWindowAndEvaluate(
                        coldLookbackMs = initialLookback,
                        isBlocking = true,
                        isTimeLimitMonitoring = isTimeLimitMonitoring
                    )
                } else {
                    if (wasBlockingActive) {
                        wasBlockingActive = false
                        lockOverlay?.dismiss()
                        FocusQuickTileService.requestListeningStateSafe(this@AppBlockerService)
                    }

                    if (isTimeLimitMonitoring || isPillHomeOnlyTracking) {
                        nextDelay = if (isTimeLimitMonitoring) 1200L else 1800L
                        checkCurrentWindowAndEvaluate(
                            coldLookbackMs = 15_000L,
                            isBlocking = false,
                            isTimeLimitMonitoring = isTimeLimitMonitoring
                        )
                    } else {
                        // Estado 100% Ocioso: atualiza pílula (caso PILL_MODE_ALWAYS / OFF) e dorme até o próximo minuto!
                        updateFloatingPillForCurrentState()
                        val now = System.currentTimeMillis()
                        nextDelay = if (prefs.hasAnyEnabledSchedule()) {
                            (60_000L - (now % 60_000L) + 150L).coerceIn(2_000L, 60_150L)
                        } else {
                            60_000L
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AppBlockerService", "Erro no monitoramento de foco", e)
            }
            handler.postDelayed(this, nextDelay)
        }
    }

    private fun checkTimedFocusCompletion(prefs: BlockPreferences) {
        val isTimerNow = prefs.isTimedFocusActive()
        if (wasTimedFocusActive && !isTimerNow && prefs.timedFocusUntilMillis > 0L) {
            prefs.stopTimedFocus()
            postTimedFocusCompletedNotification()
            FocusNotificationListenerService.syncSummaryNotification(this)
            FocusQuickTileService.requestListeningStateSafe(this)
            syncForegroundNotificationIfChanged(prefs.isBlockingCurrentlyActive())
        }
        wasTimedFocusActive = isTimerNow
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenInteractive = powerManager?.isInteractive ?: true
        registerScreenReceiverIfNeeded()

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
        val prefs = getPrefs()
        wasTimedFocusActive = prefs.isTimedFocusActive()
        startForegroundSafely(prefs.isBlockingCurrentlyActive())
        if (isScreenInteractive) {
            restartWatchdogNow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        isRunning = true
        registerScreenReceiverIfNeeded()
        isScreenInteractive = powerManager?.isInteractive ?: true
        startForegroundSafely(getPrefs().isBlockingCurrentlyActive())
        if (isScreenInteractive) {
            restartWatchdogNow()
        }
        return START_STICKY
    }

    private fun registerScreenReceiverIfNeeded() {
        if (isScreenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
            isScreenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("AppBlockerService", "Falha ao registrar receptor de estado de tela", e)
        }
    }

    private fun restartWatchdogNow() {
        handler.removeCallbacks(watchdogRunnable)
        if (isScreenInteractive) {
            handler.post(watchdogRunnable)
        }
    }

    private fun getPrefs(): BlockPreferences {
        return (application as? FocusLockApp)?.prefs ?: BlockPreferences(applicationContext)
    }

    private fun isLockUiShowing(): Boolean {
        return LockActivity.isShowing || (lockOverlay?.isShowing == true)
    }

    private fun isUsagePermissionAllowedCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUsagePermCheckMillis > 5_000L) {
            cachedUsagePermAllowed = isServiceEnabled(this)
            lastUsagePermCheckMillis = now
        }
        return cachedUsagePermAllowed
    }

    private fun syncForegroundNotificationIfChanged(isBlocking: Boolean) {
        if (lastForegroundNotifBlockingState == isBlocking) return
        startForegroundSafely(isBlocking)
    }

    private fun startForegroundSafely(isBlocking: Boolean) {
        try {
            lastForegroundNotifBlockingState = isBlocking
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

            val title = if (isBlocking) {
                "🔒 FocusLock — Modo Foco Ativo"
            } else {
                "FocusLock em Espera Inteligente"
            }
            val subtitle = if (isBlocking) {
                "Escudo anti-distração ativo (Compatível com Bancos)"
            } else {
                "Economia máxima de bateria • Pronto para suas rotinas e filtro"
            }

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_focus_shield)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
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
        coldLookbackMs: Long,
        isBlocking: Boolean,
        isTimeLimitMonitoring: Boolean
    ) {
        if (!isUsagePermissionAllowedCached()) {
            updateFloatingPillForCurrentState()
            return
        }

        val (activePkg, activeClass) = queryForegroundPackageAndClassIncremental(coldLookbackMs)
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
        if (now - lastUsageRefreshMillis > 20_000L && !isRefreshingUsage) {
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
        val isInsideSecondaryFocusLockScreen =
            FocusLockApp.isNonLockActivityInForeground && !MainActivity.isResumed
        if (!isScreenInteractive ||
            isLockUiShowing() ||
            FilteredNotificationsActivity.isResumed ||
            isInsideSecondaryFocusLockScreen
        ) {
            pill.hide()
            return
        }
        val count = NotificationFilterStore.getInstance(this).getActiveCount()
        val prefs = getPrefs()
        val isOnHomeOrMain = MainActivity.isResumed || prefs.isHomeLauncherPackage(activePkg)
        pill.updateVisibilityAndCount(count, isOnHomeOrMain)
    }

    /**
     * Lê apenas o delta incremental de eventos desde [lastQueriedEventTime] (tipicamente ~400ms com 0 eventos),
     * reduzindo o tráfego Binder IPC com o UsageStatsService em mais de 95%.
     */
    private fun queryForegroundPackageAndClassIncremental(coldLookbackMs: Long): Pair<String, String> {
        if (FocusLockApp.isNonLockActivityInForeground) {
            wasSelfInForeground = true
            currentForegroundPackage = this.packageName
            currentForegroundClass = ""
            return currentForegroundPackage to currentForegroundClass
        } else if (wasSelfInForeground) {
            wasSelfInForeground = false
            if (currentForegroundPackage == this.packageName) {
                currentForegroundPackage = ""
                currentForegroundClass = ""
            }
        }

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return currentForegroundPackage to currentForegroundClass

        val now = System.currentTimeMillis()
        val rawStart = if (lastQueriedEventTime > 0L) {
            lastQueriedEventTime - 80L
        } else {
            now - coldLookbackMs
        }
        val startQueryTime = maxOf(rawStart, minEventTimestamp)
        if (startQueryTime >= now) {
            return currentForegroundPackage to currentForegroundClass
        }

        val events = usm.queryEvents(startQueryTime, now)
        lastQueriedEventTime = now

        val event = UsageEvents.Event()
        var activePkg: String? = null
        var activeClass = ""
        var activeTime = 0L
        var sawCurrentPkgPaused = false

        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts < minEventTimestamp) continue

            val evPkg = event.packageName ?: continue
            val evCls = event.className ?: ""

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (ts >= activeTime) {
                        activeTime = ts
                        activePkg = evPkg
                        activeClass = evCls
                    }
                    if (evPkg == currentForegroundPackage) {
                        sawCurrentPkgPaused = false
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (evPkg == activePkg &&
                        (activeClass.isEmpty() || evCls.isEmpty() || evCls == activeClass) &&
                        ts >= activeTime
                    ) {
                        activePkg = null
                        activeClass = ""
                    }
                    if (evPkg == currentForegroundPackage &&
                        (currentForegroundClass.isEmpty() || evCls.isEmpty() || evCls == currentForegroundClass)
                    ) {
                        sawCurrentPkgPaused = true
                    }
                }
            }
        }

        if (!activePkg.isNullOrBlank()) {
            currentForegroundPackage = activePkg
            currentForegroundClass = activeClass
        } else if (sawCurrentPkgPaused) {
            currentForegroundPackage = ""
            currentForegroundClass = ""
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
        if (!isUsagePermissionAllowedCached() || !Settings.canDrawOverlays(this)) {
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
            startActivity(lockIntent)
        } catch (_: Exception) {
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
        handler.removeCallbacks(timerCompletionRunnable)
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {
            }
            isScreenReceiverRegistered = false
        }
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
                    // Se a pílula está no modo HOME_ONLY e acabou de chegar a 1ª notificação, acorda o rastreio leve
                    if (svc.isScreenInteractive &&
                        svc.getPrefs().floatingPillMode == BlockPreferences.PILL_MODE_HOME_ONLY &&
                        NotificationFilterStore.getInstance(svc).getActiveCount() == 1
                    ) {
                        svc.restartWatchdogNow()
                    }
                }
            }
        }

        fun onUnlocked() {
            instance?.let { svc ->
                val now = System.currentTimeMillis()
                svc.minEventTimestamp = now
                svc.lastQueriedEventTime = now
                svc.currentForegroundPackage = ""
                svc.currentForegroundClass = ""
                svc.wasBlockingActive = false
                svc.wasTimedFocusActive = false
                FocusQuickTileService.requestListeningStateSafe(svc)
                svc.handler.post {
                    svc.lockOverlay?.dismiss()
                    svc.syncForegroundNotificationIfChanged(false)
                    svc.updateFloatingPillForCurrentState()
                    svc.restartWatchdogNow()
                }
            }
        }

        fun notifyStateChanged() {
            instance?.let { svc ->
                val now = System.currentTimeMillis()
                svc.lastUsagePermCheckMillis = 0L
                if (FocusLockApp.isNonLockActivityInForeground) {
                    svc.minEventTimestamp = now
                    svc.lastQueriedEventTime = now
                    svc.currentForegroundPackage = svc.packageName
                    svc.currentForegroundClass = ""
                } else {
                    svc.lastQueriedEventTime = 0L
                }
                svc.wasBlockingActive = false
                svc.lastUsageRefreshMillis = 0L
                svc.wasTimedFocusActive = svc.getPrefs().isTimedFocusActive()
                FocusQuickTileService.requestListeningStateSafe(svc)
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
                it.minEventTimestamp = now
                it.lastQueriedEventTime = now
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
