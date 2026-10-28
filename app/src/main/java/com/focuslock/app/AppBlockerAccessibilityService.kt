package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

class AppBlockerAccessibilityService : AccessibilityService() {

    private var lastBlockedTime = 0L
    private var lastStatRecordedTime = 0L
    private var currentForegroundPackage = ""
    private val handler = Handler(Looper.getMainLooper())
    private var powerManager: PowerManager? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                // Otimização de bateria: zero processamento se a tela estiver desligada
                val isScreenOn = powerManager?.isInteractive ?: true
                if (isScreenOn) {
                    val prefs = (application as? FocusLockApp)?.prefs
                    // Só inspeciona a janela ativa se o bloqueio, horário ou filtro de Shorts/Reels estiver ativo
                    if (prefs != null && (prefs.isBlockingCurrentlyActive() || prefs.isBlockShortsReelsEnabled)) {
                        checkCurrentWindowAndBlock()
                    }
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Erro no watchdog", e)
            }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 250
            serviceInfo = info
        } catch (e: Exception) {
            Log.e("AppBlocker", "Falha ao atualizar serviceInfo", e)
        }

        handler.removeCallbacks(watchdogRunnable)
        handler.post(watchdogRunnable)
        Log.d("AppBlocker", "Accessibility Service conectado e monitorando.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            evaluateAndBlockPackage(pkg)
        }
    }

    private fun checkCurrentWindowAndBlock() {
        val activePkg = rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage
        if (activePkg.isNotBlank()) {
            evaluateAndBlockPackage(activePkg)
        }
    }

    private fun evaluateAndBlockPackage(packageName: String) {
        if (packageName.isBlank()) return

        // Se a própria tela de bloqueio já está visível no topo, não dispara novamente
        if (packageName == this.packageName) {
            return
        }

        val prefs = (application as? FocusLockApp)?.prefs ?: BlockPreferences(applicationContext)

        // Ignorar janelas do sistema/teclado para não sobrescrever o pacote em primeiro plano real
        if (!prefs.isAlwaysAllowed(packageName)) {
            currentForegroundPackage = packageName
        }

        // Otimização de bateria: se não há bloqueio manual, horário ativo nem filtro Shorts/Reels ativo, retorna imediatamente
        if ((!prefs.isBlockingCurrentlyActive() && !prefs.isBlockShortsReelsEnabled) || LockActivity.isShowing) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < 250L) {
            return
        }

        // Bloqueio seletivo de YouTube Shorts e Instagram Reels
        if (prefs.isBlockShortsReelsEnabled && isShortsOrReelsScreenActive(packageName)) {
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchLockScreen("Shorts / Reels (Feed Curto)")
            return
        }

        if (!prefs.isBlockingCurrentlyActive()) {
            return
        }

        // Proteção Anti-Burla (Strict Mode): bloqueia tentativa de forçar parada/desinstalar/desativar o FocusLock nas Configurações
        if (prefs.isAntiTamperEnabled && isSettingsTamperAttempt(packageName)) {
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchLockScreen("Proteção Anti-Burla (Configurações)")
            return
        }

        if (prefs.shouldBlockNow(packageName)) {
            lastBlockedTime = now
            if (now - lastStatRecordedTime > 3000L) {
                lastStatRecordedTime = now
                prefs.recordBlockedAttempt()
            }
            Log.d("AppBlocker", "Bloqueando pacote restrito: $packageName")
            launchLockScreen(packageName)
        }
    }

    private fun isShortsOrReelsScreenActive(packageName: String): Boolean {
        if (packageName != "com.google.android.youtube" && packageName != "com.instagram.android") {
            return false
        }
        val root = rootInActiveWindow ?: return false
        val shortsReelsViewIds = listOf(
            "reel_player_page_container",
            "reel_recycler",
            "reel_watch_fragment_root",
            "shorts_player",
            "clips_viewer_view_pager",
            "clips_video_container",
            "reel_viewer_root"
        )
        return hasMatchingViewId(root, shortsReelsViewIds, 0)
    }

    private fun hasMatchingViewId(
        node: AccessibilityNodeInfo?,
        markers: List<String>,
        depth: Int
    ): Boolean {
        if (node == null || depth > 8) return false
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.isNotEmpty() && markers.any { viewId.contains(it) }) {
            return true
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (hasMatchingViewId(node.getChild(i), markers, depth + 1)) {
                return true
            }
        }
        return false
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

    private fun isSettingsTamperAttempt(packageName: String): Boolean {
        val lowerPkg = packageName.lowercase()
        val isSettingsOrInstaller = packageName == "com.android.settings" ||
                lowerPkg.contains("packageinstaller") ||
                lowerPkg.contains("securitycenter")
        if (!isSettingsOrInstaller) return false

        val root = rootInActiveWindow ?: return false
        val collected = StringBuilder()
        collectNodeText(root, collected, 0)
        val screenText = collected.toString().lowercase()

        if (!screenText.contains("focuslock")) {
            return false
        }

        val tamperKeywords = listOf(
            "forçar parada",
            "force stop",
            "forçar interrupção",
            "desinstalar",
            "uninstall",
            "usar focuslock",
            "use focuslock",
            "desativar serviço",
            "stop focuslock",
            "limpar dados",
            "clear storage",
            "clear data"
        )
        return tamperKeywords.any { screenText.contains(it) }
    }

    private fun collectNodeText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 7 || sb.length > 3000) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        val count = node.childCount
        for (i in 0 until count) {
            collectNodeText(node.getChild(i), sb, depth + 1)
        }
    }

    override fun onInterrupt() {
        Log.d("AppBlocker", "Accessibility Service interrompido.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(watchdogRunnable)
        isRunning = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        isRunning = false
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: AppBlockerAccessibilityService? = null
            private set

        fun isServiceEnabled(context: Context): Boolean {
            if (isRunning) return true

            try {
                val enabledServicesSetting = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (!enabledServicesSetting.isNullOrBlank()) {
                    val expected1 = "${context.packageName}/${AppBlockerAccessibilityService::class.java.canonicalName}"
                    val expected2 = "${context.packageName}/.AppBlockerAccessibilityService"
                    if (enabledServicesSetting.contains(expected1, ignoreCase = true) ||
                        enabledServicesSetting.contains(expected2, ignoreCase = true) ||
                        enabledServicesSetting.contains(context.packageName, ignoreCase = true)
                    ) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }

            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                enabledServices.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
            } catch (_: Exception) {
                false
            }
        }
    }
}
