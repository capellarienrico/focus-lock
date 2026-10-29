package com.focuslock.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Botão na Barra de Configurações Rápidas do Android (Quick Settings Tile).
 *
 * - Consome 0% de bateria em segundo plano (gerenciado sob demanda pela SystemUI do Android).
 * - Quando desbloqueado: inicia instantaneamente uma sessão rápida de foco (25 min Pomodoro) com 1 toque.
 * - Quando bloqueado: exibe status ativo e abre o FocusLock (nunca desbloqueia sem Senha Mestre / Tag NFC).
 */
class FocusQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileVisuals()
    }

    override fun onClick() {
        super.onClick()
        val prefs = (application as? FocusLockApp)?.prefs ?: BlockPreferences(applicationContext)

        if (prefs.isBlockingCurrentlyActive()) {
            Toast.makeText(
                this,
                "🔒 Foco ativo! Use sua Tag NFC ou Senha Mestre no FocusLock para desbloquear.",
                Toast.LENGTH_LONG
            ).show()
            openMainActivityAndCollapse()
            return
        }

        val hasPermissions = prefs.isMasterPasswordConfigured &&
                AppBlockerService.isServiceEnabled(this) &&
                Settings.canDrawOverlays(this)

        if (!hasPermissions) {
            Toast.makeText(
                this,
                "Configure a Senha Mestre e permissões no FocusLock primeiro.",
                Toast.LENGTH_LONG
            ).show()
            openMainActivityAndCollapse()
            return
        }

        val mins = prefs.timedFocusDurationMinutes.takeIf { it in 5..240 } ?: 25
        prefs.startTimedFocusMinutes(mins)
        AppBlockerService.ensureRunning(this)
        AppBlockerService.notifyStateChanged()
        FocusNotificationListenerService.syncSummaryNotification(this)
        refreshTileVisuals()

        Toast.makeText(
            this,
            "⏳ Sessão de foco iniciada por $mins min!",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openMainActivityAndCollapse() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    2301,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshTileVisuals() {
        val tile = qsTile ?: return
        val prefs = (application as? FocusLockApp)?.prefs ?: BlockPreferences(applicationContext)
        val isActive = prefs.isBlockingCurrentlyActive()

        tile.icon = Icon.createWithResource(this, R.drawable.ic_focus_shield)
        if (isActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Foco Ativo"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (prefs.isTimedFocusActive()) {
                    val remMins = ((prefs.getTimedFocusRemainingMillis() / 60_000L) + 1L)
                    "${remMins}m restantes"
                } else {
                    "Protegido"
                }
            }
        } else {
            val defaultMins = prefs.timedFocusDurationMinutes.takeIf { it in 5..240 } ?: 25
            tile.state = Tile.STATE_INACTIVE
            tile.label = "FocusLock"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Iniciar ${defaultMins}m"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestListeningStateSafe(context: Context) {
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, FocusQuickTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
