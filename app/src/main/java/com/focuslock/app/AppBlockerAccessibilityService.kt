package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppBlockerAccessibilityService : AccessibilityService() {

    private var lastBlockedTime = 0L
    private var lastBlockedPackage = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // Debounce para evitar disparos repetidos no mesmo milissegundo
        if (packageName == lastBlockedPackage && (now - lastBlockedTime < 700L)) {
            return
        }

        val prefs = (application as? FocusLockApp)?.prefs ?: return

        if (prefs.shouldBlockNow(packageName)) {
            lastBlockedTime = now
            lastBlockedPackage = packageName
            Log.d("AppBlocker", "Bloqueando pacote restrito: $packageName")

            // Abre a tela de bloqueio com flags adequadas
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
