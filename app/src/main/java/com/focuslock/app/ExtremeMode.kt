package com.focuslock.app

import android.content.Context
import android.provider.Settings
import android.widget.Toast

/**
 * Modo EXTREME: Perfil pré-definido onde todos os apps não-essenciais são bloqueados ao abrir.
 * Apenas Câmera, Telefone/chamadas, Tela Inicial, Teclado e o próprio FocusLock ficam liberados.
 * Para sair: Senha Forte EXTREME ou Tag NFC EXTREME dedicada.
 */
object ExtremeMode {

    fun activate(context: Context): String? {
        val prefs = (context.applicationContext as FocusLockApp).prefs
        if (!prefs.hasExtremePassword()) return "Defina a Senha Forte EXTREME antes de ativar."
        if (!Settings.canDrawOverlays(context)) return "Conceda a permissão 'Sobrepor a Outros Apps' primeiro."
        if (!AppBlockerService.isServiceEnabled(context)) return "Conceda o 'Acesso ao Uso de Apps' primeiro."
        prefs.isExtremeActive = true
        AppBlockerService.ensureRunning(context)
        AppBlockerService.notifyStateChanged()
        return null
    }

    fun deactivate(context: Context) {
        val prefs = (context.applicationContext as FocusLockApp).prefs
        prefs.isExtremeActive = false
        prefs.resetExtremeFailures()
        AppBlockerService.onUnlocked()
        FocusNotificationListenerService.syncSummaryNotification(context)
    }

    fun handleTag(context: Context, uidHex: String): Boolean {
        val prefs = (context.applicationContext as FocusLockApp).prefs
        if (!prefs.isExtremeNfcTag(uidHex)) return false
        if (prefs.isExtremeActive) {
            deactivate(context)
            Toast.makeText(context, "Modo EXTREME desativado via NFC!", Toast.LENGTH_LONG).show()
        } else {
            val err = activate(context)
            Toast.makeText(
                context,
                err ?: "Modo EXTREME ATIVADO via NFC!",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }
}
