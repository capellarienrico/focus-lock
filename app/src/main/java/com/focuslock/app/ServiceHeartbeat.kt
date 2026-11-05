package com.focuslock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Heartbeat de auto-recuperação.
 *
 * O monitor (AppBlockerService) e o filtro (FocusNotificationListenerService) vivem no mesmo
 * processo. Se o sistema/OEM mata o processo (economia de bateria, limpeza de memória, crash),
 * nada os reiniciava até o usuário abrir o app. Um alarme leve (a cada ~5 min, tolerante a Doze)
 * garante que ambos sejam religados sozinhos.
 */
object ServiceHeartbeat {
    private const val TAG = "ServiceHeartbeat"
    private const val REQUEST_CODE = 3002
    private const val INTERVAL_MS = 5 * 60_000L

    fun schedule(context: Context) {
        try {
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            val pi = pendingIntent(appCtx)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao agendar heartbeat", e)
        }
    }

    /** Chamado a cada batimento: religa o monitor e o listener se necessário e reagenda. */
    fun onBeat(context: Context) {
        val appCtx = context.applicationContext
        try {
            if (!AppBlockerService.isRunning) {
                AppBlockerService.ensureRunning(appCtx)
            }
            requestListenerRebindIfNeeded(appCtx)
        } catch (e: Exception) {
            Log.w(TAG, "Falha no heartbeat", e)
        } finally {
            schedule(appCtx)
        }
    }

    fun requestListenerRebindIfNeeded(context: Context) {
        try {
            val appCtx = context.applicationContext
            if (FocusNotificationListenerService.isConnected) return
            if (!FocusNotificationListenerService.isNotificationAccessEnabled(appCtx)) return

            Log.i(TAG, "Forçando rebind do FocusNotificationListenerService via alternância de componente...")
            val componentName = ComponentName(appCtx, FocusNotificationListenerService::class.java)
            val pm = appCtx.packageManager

            // Truque comprovado no ecossistema Android (Samsung OneUI, Xiaomi HyperOS/MIUI, Motorola, etc.):
            // Alternar o estado do componente (DISABLED -> ENABLED) notifica internamente o NotificationManagerService
            // do Android de uma mudança de pacote, destruindo qualquer binder morto preso no sistema e
            // forçando o re-bind imediato e seguro do serviço de notificações.
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    NotificationListenerService.requestRebind(componentName)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao pedir rebind do listener", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_HEARTBEAT
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
