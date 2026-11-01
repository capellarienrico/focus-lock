package com.focuslock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pendingIntent(appCtx)
            )
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
            NotificationListenerService.requestRebind(
                ComponentName(appCtx, FocusNotificationListenerService::class.java)
            )
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
