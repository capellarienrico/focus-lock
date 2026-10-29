package com.focuslock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Filtro de Notificações inspirado no Minimalist Phone.
 *
 * Intercepta e silencia notificações dos aplicativos selecionados pelo usuário, armazenando-as
 * em uma caixa minimalista e exibindo apenas um resumo silencioso: "X notificações filtradas".
 *
 * 100% seguro com aplicativos bancários (não usa AccessibilityService e jamais lê/altera
 * notificações de bancos, chamadas telefônicas ou alarmes).
 */
class FocusNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        syncSummaryNotification(applicationContext)
    }

    override fun onListenerDisconnected() {
        isConnected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == this.packageName) return

        try {
            val prefs = (application as? FocusLockApp)?.prefs ?: BlockPreferences(applicationContext)
            if (!prefs.shouldFilterNotificationsNow()) return

            // Nunca intercepta notificações permanentes, chamadas, alarmes ou apps de banco/sistema
            if (sbn.isOngoing || !sbn.isClearable) return
            val notif = sbn.notification ?: return
            if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
            if ((notif.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
            if (notif.category == Notification.CATEGORY_CALL ||
                notif.category == Notification.CATEGORY_ALARM ||
                notif.category == Notification.CATEGORY_SYSTEM
            ) return

            if (prefs.isBankingOrCriticalSystemPackage(pkg)) return

            val filteredPkgs = prefs.getFilteredNotificationPackages()
            if (!filteredPkgs.contains(pkg)) return

            // Ignora notificações-resumo de grupo (FLAG_GROUP_SUMMARY) como "WhatsApp: 3 novas mensagens",
            // evitando contar em dobro cada mensagem individual que chega!
            val isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (isGroupSummary) {
                cancelNotification(sbn.key)
                return
            }

            val extras = notif.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                ?: extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
                ?: ""
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            val normalText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            val textLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n") { it.toString().trim() }
                ?.takeIf { it.isNotBlank() }

            val body = when {
                !bigText.isNullOrBlank() -> bigText
                !normalText.isNullOrBlank() -> normalText
                !textLines.isNullOrBlank() -> textLines
                else -> ""
            }

            val appName = try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }

            val store = NotificationFilterStore.getInstance(applicationContext)
            if (title.isNotBlank() || body.isNotBlank()) {
                store.addFilteredNotification(
                    packageName = pkg,
                    appName = appName,
                    title = title.ifBlank { appName },
                    text = body,
                    timestamp = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis(),
                    contentIntent = notif.contentIntent
                )
            }

            // Remove silenciosamente a notificação original da barra de status
            cancelNotification(sbn.key)

            // Atualiza a notificação consolidada "X notificações filtradas" e a pílula flutuante
            syncSummaryNotification(applicationContext)
        } catch (e: Exception) {
            Log.e("FocusNotifFilter", "Erro ao filtrar notificação de $pkg", e)
        }
    }

    companion object {
        private const val SUMMARY_CHANNEL_ID = "focus_lock_filtered_summary_channel"
        const val SUMMARY_NOTIFICATION_ID = 1002

        @Volatile
        var isConnected: Boolean = false
            private set

        fun isNotificationAccessEnabled(context: Context): Boolean {
            return try {
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            } catch (_: Exception) {
                false
            }
        }

        fun syncSummaryNotification(context: Context) {
            val appCtx = context.applicationContext
            val store = NotificationFilterStore.getInstance(appCtx)
            val items = store.getNotifications()
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Atualiza também a pílula flutuante minimalista via AppBlockerService
            AppBlockerService.refreshFloatingFilterPill()

            val prefs = (appCtx as? FocusLockApp)?.prefs ?: BlockPreferences(appCtx)
            if (!prefs.isNotificationFilterEnabled || items.isEmpty()) {
                nm.cancel(SUMMARY_NOTIFICATION_ID)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SUMMARY_CHANNEL_ID,
                    "Resumo de Notificações Filtradas",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Exibe o contador silencioso de notificações filtradas (estilo Minimalist Phone)"
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            }

            val count = items.size
            val title = if (count == 1) {
                "🔕 1 notificação filtrada"
            } else {
                "🔕 $count notificações filtradas"
            }
            val subtitle = "Toque para revisar quando quiser"

            val inboxIntent = Intent(appCtx, FilteredNotificationsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                appCtx,
                2101,
                inboxIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val clearIntent = Intent(appCtx, BootReceiver::class.java).apply {
                action = BootReceiver.ACTION_CLEAR_FILTERED_NOTIFICATIONS
            }
            val clearPi = PendingIntent.getBroadcast(
                appCtx,
                2102,
                clearIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Privacidade & Minimalismo: exibe APENAS o total de notificações filtradas,
            // sem mostrar remetente, nome do app nem prévia da mensagem na barra de notificações.
            val notification = NotificationCompat.Builder(appCtx, SUMMARY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_focus_shield)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setNumber(count)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOngoing(false)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(0, "Abrir ($count)", openPi)
                .addAction(0, "Limpar Tudo", clearPi)
                .build()

            nm.notify(SUMMARY_NOTIFICATION_ID, notification)
        }
    }
}
