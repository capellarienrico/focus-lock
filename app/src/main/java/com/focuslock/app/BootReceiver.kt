package com.focuslock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            ACTION_RESTART_SERVICE -> {
                AppBlockerService.ensureRunning(context)
                FocusNotificationListenerService.syncSummaryNotification(context)
            }
            ACTION_CLEAR_FILTERED_NOTIFICATIONS -> {
                NotificationFilterStore.getInstance(context).clearAll()
                FocusNotificationListenerService.syncSummaryNotification(context)
            }
        }
    }

    companion object {
        const val ACTION_RESTART_SERVICE = "com.focuslock.zen.ACTION_RESTART_SERVICE"
        const val ACTION_CLEAR_FILTERED_NOTIFICATIONS = "com.focuslock.zen.ACTION_CLEAR_FILTERED_NOTIFICATIONS"
    }
}
