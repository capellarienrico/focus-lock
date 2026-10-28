package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

class AppBlockerAccessibilityService : AccessibilityService() {

    private var lastBlockedTime = 0L
    private var currentForegroundPackage = ""
    private val handler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                checkCurrentWindowAndBlock()
            } catch (e: Exception) {
                Log.e("AppBlocker", "Erro no watchdog", e)
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true

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
            info.notificationTimeout = 100
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

        if (LockActivity.isShowing) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastBlockedTime < 250L) {
            return
        }

        if (prefs.shouldBlockNow(packageName)) {
            lastBlockedTime = now
            Log.d("AppBlocker", "Bloqueando pacote restrito: $packageName")

            val lockIntent = Intent(this, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra("BLOCKED_PACKAGE", packageName)
            }
            startActivity(lockIntent)
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
