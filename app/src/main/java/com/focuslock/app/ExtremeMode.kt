package com.focuslock.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.CountDownTimer
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Modo EXTREME: o celular inteiro fica travado (exceto Camera e Telefone/chamadas).
 * Só sai com a Senha Forte EXTREME (diferente da Senha Mestre) ou com uma Tag NFC EXTREME
 * (diferente das Tags NFC normais). Nem a Senha Mestre nem as Tags normais liberam.
 */
object ExtremeMode {
    private const val TAG = "ExtremeMode"

    /** Ativa o modo. Retorna mensagem de erro, ou null se ativou. */
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
        ExtremeLockActivity.finishIfShowing()
        FocusNotificationListenerService.syncSummaryNotification(context)
    }

    /**
     * Trata uma Tag NFC lida. Retorna true se a tag é uma Tag EXTREME (e foi tratada).
     * Com o modo ativo: desativa. Com o modo inativo: ativa (se permissões OK).
     */
    fun handleTag(context: Context, uidHex: String): Boolean {
        val prefs = (context.applicationContext as FocusLockApp).prefs
        if (!prefs.isExtremeNfcTag(uidHex)) return false
        if (prefs.isExtremeActive) {
            deactivate(context)
            Toast.makeText(context, "Modo EXTREME desativado via NFC.", Toast.LENGTH_LONG).show()
        } else {
            val err = activate(context)
            Toast.makeText(
                context,
                err ?: "Modo EXTREME ATIVADO via NFC.",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    fun launchCamera(context: Context) {
        val intents = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(context, "Câmera não encontrada.", Toast.LENGTH_SHORT).show()
    }

    fun launchDialer(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao abrir discador", e)
            Toast.makeText(context, "Discador não encontrado.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Abre a tela de bloqueio EXTREME (usada pelo MainActivity e pelo overlay). */
    fun openLockScreen(context: Context) {
        try {
            context.startActivity(
                Intent(context, ExtremeLockActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao abrir ExtremeLockActivity", e)
        }
    }
}

/**
 * Painel de UI (construído por código) compartilhado entre o overlay e a [ExtremeLockActivity].
 */
class ExtremePanel(
    private val context: Context,
    private val prefs: BlockPreferences,
    private val onUnlocked: () -> Unit,
    private val onNfcCardTap: (() -> Unit)? = null
) {
    val etPassword: EditText
    val root: View
    private val tvError: TextView
    private val btnUnlock: Button
    private var lockoutTimer: CountDownTimer? = null

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2B3A55"))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
    }

    init {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(48), dp(28), dp(28))
        }
        col.addView(label("🔥", 56f, Color.WHITE))
        col.addView(label("MODO EXTREME", 26f, Color.parseColor("#FF5252"), true))
        col.addView(
            label(
                "Celular travado. Só Câmera e Telefone estão liberados.\n" +
                        "Para sair: Senha Forte EXTREME ou a Tag NFC EXTREME.",
                14f, Color.parseColor("#B0B8C8")
            ).apply { setPadding(0, dp(8), 0, dp(20)) }
        )

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("📷 Câmera") { ExtremeMode.launchCamera(context) })
        row.addView(actionButton("📞 Telefone") { ExtremeMode.launchDialer(context) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))

        val nfcCard = label(
            "📡 Aproxime a Tag NFC EXTREME para desbloquear",
            14f, Color.parseColor("#7CB8FF")
        ).apply {
            setPadding(dp(12), dp(18), dp(12), dp(18))
            setBackgroundColor(Color.parseColor("#1B2638"))
            setOnClickListener { onNfcCardTap?.invoke() }
        }
        col.addView(nfcCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        etPassword = EditText(context).apply {
            hint = "Senha Forte EXTREME"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7C8598"))
        }
        col.addView(etPassword, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        btnUnlock = Button(context).apply {
            text = "Desbloquear com Senha"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setOnClickListener { submit() }
        }
        col.addView(btnUnlock, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })

        tvError = label("", 13f, Color.parseColor("#FF8A80")).apply { visibility = View.GONE }
        col.addView(tvError, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0D1117"))
            addView(col, FrameLayout.LayoutParams(-1, -2))
        }
        refreshLockoutState()
    }

    private fun submit() {
        val remaining = prefs.extremeLockoutRemainingMs()
        if (remaining > 0) {
            refreshLockoutState()
            return
        }
        val entered = etPassword.text?.toString() ?: ""
        if (prefs.verifyExtremePassword(entered)) {
            lockoutTimer?.cancel()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etPassword.windowToken, 0)
            ExtremeMode.deactivate(context)
            Toast.makeText(context, "Modo EXTREME desativado.", Toast.LENGTH_LONG).show()
            onUnlocked()
        } else {
            etPassword.text?.clear()
            if (prefs.extremeLockoutRemainingMs() > 0) {
                refreshLockoutState()
            } else {
                tvError.text = "Senha incorreta."
                tvError.visibility = View.VISIBLE
            }
        }
    }

    /** Mostra/atualiza a contagem regressiva quando há bloqueio por tentativas erradas. */
    fun refreshLockoutState() {
        lockoutTimer?.cancel()
        val remaining = prefs.extremeLockoutRemainingMs()
        if (remaining <= 0) {
            btnUnlock.isEnabled = true
            return
        }
        btnUnlock.isEnabled = false
        tvError.visibility = View.VISIBLE
        lockoutTimer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(ms: Long) {
                tvError.text = "Muitas tentativas erradas. Aguarde ${ms / 1000 + 1}s."
            }

            override fun onFinish() {
                btnUnlock.isEnabled = true
                tvError.text = "Tente novamente."
            }
        }.start()
    }

    fun release() {
        lockoutTimer?.cancel()
        lockoutTimer = null
    }
}

/**
 * Overlay de tela cheia do modo EXTREME. Cobre qualquer app (inclusive Home, Configurações e este app)
 * e é escondido pelo serviço apenas enquanto Câmera ou Telefone estão em primeiro plano.
 */
class ExtremeOverlayController(
    private val serviceContext: Context,
    private val prefsProvider: () -> BlockPreferences
) {
    @Volatile
    var isShowing: Boolean = false
        private set

    private var wm: WindowManager? = null
    private var wrapper: FrameLayout? = null
    private var panel: ExtremePanel? = null

    fun show(): Boolean {
        if (isShowing) return true
        if (!Settings.canDrawOverlays(serviceContext)) return false
        return try {
            val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = windowManager
            val p = ExtremePanel(
                context = serviceContext,
                prefs = prefsProvider(),
                onUnlocked = { dismiss() },
                onNfcCardTap = { ExtremeMode.openLockScreen(serviceContext) }
            )
            panel = p
            val w = object : FrameLayout(serviceContext) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                addView(p.root, FrameLayout.LayoutParams(-1, -1))
            }
            wrapper = w
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            ).apply {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                title = "FocusLockExtremeShield"
            }
            isShowing = true
            windowManager.addView(w, params)
            w.requestFocus()
            // Abre a Activity por baixo apenas para habilitar a leitura de NFC (foreground dispatch).
            w.post { if (isShowing) ExtremeMode.openLockScreen(serviceContext) }
            true
        } catch (e: Exception) {
            Log.e("ExtremeOverlay", "Erro ao exibir overlay EXTREME", e)
            isShowing = false
            wrapper = null
            panel = null
            false
        }
    }

    fun dismiss() {
        val w = wrapper
        val manager = wm
        panel?.release()
        panel = null
        wrapper = null
        isShowing = false
        if (w != null && manager != null) {
            try {
                manager.removeViewImmediate(w)
            } catch (_: Exception) {
                try {
                    manager.removeView(w)
                } catch (_: Exception) {
                }
            }
        }
    }
}
