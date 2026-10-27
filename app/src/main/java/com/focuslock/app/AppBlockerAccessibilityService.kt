package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppBlockerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val prefs = (application as? FocusLockApp)?.prefs ?: return

        if (prefs.shouldBlockNow(packageName)) {
            Log.d("AppBlocker", "Bloqueando pacote restrito: $packageName")

            // 1. Volta imediatamente para a tela inicial
            performGlobalAction(GLOBAL_ACTION_HOME)

            // 2. Abre a tela de bloqueio com aviso e desbloqueio
            val lockIntent = Intent(this, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("BLOCKED_PACKAGE", packageName)
            }
            startActivity(lockIntent)
        }
    }

    override fun onInterrupt() {
        Log.d("AppBlocker", "Accessibility Service interrompido.")
    }
}
