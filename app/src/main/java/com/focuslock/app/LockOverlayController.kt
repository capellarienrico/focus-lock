package com.focuslock.app

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.focuslock.app.databinding.ActivityLockBinding
import java.security.SecureRandom

/**
 * Renderiza a tela de bloqueio diretamente via WindowManager (TYPE_APPLICATION_OVERLAY).
 *
 * Isso garante bloqueio visual e de toque instantâneo (0ms) em todas as versões do Android
 * (incluindo Android 14, 15 e 16 e sistemas Samsung OneUI, Xiaomi HyperOS/MIUI e Motorola),
 * sem depender de Background Activity Launch (BAL) nem de AccessibilityService.
 */
class LockOverlayController(
    private val serviceContext: Context,
    private val prefsProvider: () -> BlockPreferences,
    private val onOverlayVisibleCallback: (String) -> Unit
) {

    @Volatile
    var isShowing: Boolean = false
        private set

    private var windowManager: WindowManager? = null
    private var rootWrapper: FrameLayout? = null
    private var binding: ActivityLockBinding? = null
    private var mindfulTimer: CountDownTimer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentBlockedReason: String = ""
    private var expectedCognitiveAnswer: Int = 0

    fun show(blockedPkgOrReason: String): Boolean {
        if (!Settings.canDrawOverlays(serviceContext)) {
            return false
        }

        currentBlockedReason = blockedPkgOrReason

        if (isShowing && rootWrapper != null && binding != null) {
            binding?.let { b ->
                updateBlockedAppLabel(b, blockedPkgOrReason)
            }
            return true
        }

        return try {
            val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val themedContext = ContextThemeWrapper(serviceContext, R.style.Theme_FocusLock_LockScreen)
            val b = ActivityLockBinding.inflate(LayoutInflater.from(themedContext))
            binding = b

            val wrapper = object : FrameLayout(themedContext) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            goToHome()
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(themedContext, R.color.bg_dark))
                addView(
                    b.root,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
            rootWrapper = wrapper

            updateBlockedAppLabel(b, blockedPkgOrReason)
            setupOverlayInteractions(b)
            applyStrictnessModeUI(b)
            requestTransientAudioPause()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            ).apply {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                title = "FocusLockShield"
            }

            isShowing = true
            wm.addView(wrapper, params)
            wrapper.requestFocus()

            // Assim que a janela TYPE_APPLICATION_OVERLAY estiver anexada e visível na tela,
            // o Android 14/15/16 concede permissão oficial de janela visível para abrir a LockActivity
            // (habilitando leitura de Tag NFC via ForegroundDispatch e pausando o app restrito ao fundo).
            wrapper.post {
                if (isShowing) {
                    onOverlayVisibleCallback(blockedPkgOrReason)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("LockOverlayController", "Erro ao exibir overlay de bloqueio", e)
            isShowing = false
            rootWrapper = null
            binding = null
            false
        }
    }

    fun dismiss() {
        mindfulTimer?.cancel()
        mindfulTimer = null
        abandonAudioPause()

        val wrapper = rootWrapper
        val wm = windowManager
        val b = binding

        isShowing = false
        rootWrapper = null
        binding = null

        if (wrapper != null && wm != null) {
            try {
                val imm = serviceContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                b?.etPin?.windowToken?.let { token ->
                    imm?.hideSoftInputFromWindow(token, 0)
                }
                wm.removeViewImmediate(wrapper)
            } catch (_: Exception) {
                try {
                    wm.removeView(wrapper)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setupOverlayInteractions(b: ActivityLockBinding) {
        b.btnGoHome.setOnClickListener {
            goToHome()
        }

        b.cardNfcPrompt.setOnClickListener {
            onOverlayVisibleCallback(currentBlockedReason)
        }

        b.btnUnlockWithPin.setOnClickListener {
            val enteredPin = b.etPin.text?.toString()?.trim() ?: ""
            val prefs = prefsProvider()
            val masterPin = prefs.masterPin

            if (enteredPin.isNotEmpty() && enteredPin == masterPin) {
                b.tvError.visibility = View.GONE
                hideKeyboard(b.etPin)
                if (prefs.isCognitiveChallengeEnabled || prefs.isDailyBudgetExhausted()) {
                    showInlineCognitiveChallenge(b)
                } else {
                    handlePinVerified(b)
                }
            } else {
                vibrateError()
                b.tvError.text = "Senha Mestre incorreta. Tente novamente."
                b.tvError.visibility = View.VISIBLE
                b.etPin.text?.clear()
            }
        }

        b.btnConfirmCognitive.setOnClickListener {
            val ans = b.etCognitiveAnswer.text?.toString()?.trim()?.toIntOrNull()
            if (ans == expectedCognitiveAnswer) {
                b.tvError.visibility = View.GONE
                b.llCognitiveSection.visibility = View.GONE
                hideKeyboard(b.etCognitiveAnswer)
                handlePinVerified(b)
            } else {
                vibrateError()
                b.tvError.text = "Resposta incorreta! Resolva o cálculo acima para continuar."
                b.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun showInlineCognitiveChallenge(b: ActivityLockBinding) {
        val random = SecureRandom()
        val x = random.nextInt(12) + 7 // 7..18
        val y = random.nextInt(8) + 4  // 4..11
        val z = random.nextInt(35) + 12 // 12..46
        expectedCognitiveAnswer = (x * y) + z

        b.llPinSection.visibility = View.GONE
        b.llCognitiveSection.visibility = View.VISIBLE
        b.etCognitiveAnswer.text?.clear()
        b.tvCognitivePrompt.text =
            "🧠 Desafio Cognitivo (Anti-Impulso)\nResolva o cálculo para confirmar o desbloqueio:\n\n($x × $y) + $z = ?"
    }

    private fun handlePinVerified(b: ActivityLockBinding) {
        mindfulTimer?.cancel()
        val prefs = prefsProvider()
        val hadActiveSchedule = prefs.getSchedules().any { it.isActiveNow() }
        prefs.isManualLockActive = false

        if (prefs.isDailyBudgetEnabled) {
            prefs.incrementDailyOpensUsed()
        }

        vibrateSuccess()

        if (hadActiveSchedule) {
            if (prefs.isDailyBudgetEnabled) {
                val mins = prefs.dailySessionMinutes
                prefs.snoozeSchedulesForMinutes(mins)
                val used = prefs.getDailyOpensUsedToday()
                val max = prefs.dailyMaxOpens
                Toast.makeText(
                    serviceContext,
                    "Desbloqueado com Senha Mestre! (Liberado por $mins min • Abertura $used/$max hoje)",
                    Toast.LENGTH_LONG
                ).show()
                AppBlockerService.onUnlocked()
                dismiss()
                return
            }

            // Exibe seletor inline de tempo de liberação (sem AlertDialog para compatibilidade com Overlay)
            b.llPinSection.visibility = View.GONE
            b.llMindfulSection.visibility = View.GONE
            b.llCognitiveSection.visibility = View.GONE
            b.llSnoozeSection.visibility = View.VISIBLE

            val applySnooze: (Int) -> Unit = { mins ->
                prefs.snoozeSchedulesForMinutes(mins)
                Toast.makeText(
                    serviceContext,
                    "Desbloqueado com Senha Mestre! (Liberado por $mins min)",
                    Toast.LENGTH_LONG
                ).show()
                AppBlockerService.onUnlocked()
                dismiss()
            }

            b.btnSnooze1m.setOnClickListener { applySnooze(1) }
            b.btnSnooze5m.setOnClickListener { applySnooze(5) }
            b.btnSnooze15m.setOnClickListener { applySnooze(15) }
            b.btnSnooze30m.setOnClickListener { applySnooze(30) }
        } else {
            Toast.makeText(serviceContext, "Desbloqueado com Senha Mestre!", Toast.LENGTH_SHORT).show()
            AppBlockerService.onUnlocked()
            dismiss()
        }
    }

    private fun applyStrictnessModeUI(b: ActivityLockBinding) {
        mindfulTimer?.cancel()
        val prefs = prefsProvider()
        val mode = prefs.strictnessMode

        b.llCognitiveSection.visibility = View.GONE
        b.llSnoozeSection.visibility = View.GONE
        b.tvError.visibility = View.GONE

        when (mode) {
            BlockPreferences.MODE_DEEP_FOCUS_NFC -> {
                b.llMindfulSection.visibility = View.VISIBLE
                b.llPinSection.visibility = View.GONE
                b.tvMindfulPrompt.text =
                    "🔒 Modo Deep Focus (Opal / Brick)\nDesbloqueio prioritário via Tag NFC física."
                b.tvMindfulCountdown.visibility = View.GONE
                b.btnEmergencyPinUnlock.visibility = View.VISIBLE
                b.btnEmergencyPinUnlock.setOnClickListener {
                    b.btnEmergencyPinUnlock.visibility = View.GONE
                    b.tvMindfulCountdown.visibility = View.VISIBLE
                    startCountdownToRevealPin(b, 30)
                }
            }
            BlockPreferences.MODE_MINDFUL_DELAY -> {
                b.llMindfulSection.visibility = View.VISIBLE
                b.llPinSection.visibility = View.GONE
                b.tvMindfulPrompt.text =
                    "🧘 Pausa Consciente (ScreenZen)\nRespire fundo. Você realmente precisa abrir este app agora?"
                b.tvMindfulCountdown.visibility = View.VISIBLE
                b.btnEmergencyPinUnlock.visibility = View.GONE
                startCountdownToRevealPin(b, 15)
            }
            else -> {
                b.llMindfulSection.visibility = View.GONE
                b.llPinSection.visibility = View.VISIBLE
            }
        }
    }

    private fun startCountdownToRevealPin(b: ActivityLockBinding, seconds: Int) {
        mindfulTimer?.cancel()
        mindfulTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L) + 1
                b.tvMindfulCountdown.text = "Liberando campo de Senha Mestre em ${secLeft}s..."
            }

            override fun onFinish() {
                b.llMindfulSection.visibility = View.GONE
                b.llPinSection.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun updateBlockedAppLabel(b: ActivityLockBinding, pkg: String) {
        val prefs = prefsProvider()
        val attempts = prefs.getBlockedAttemptsToday()
        b.tvBlockedStats.text = "🛡️ $attempts tentativa(s) de distração bloqueada(s) hoje"
        b.tvCustomQuote.text = "\"${prefs.customLockQuote}\""

        if (prefs.isDailyBudgetEnabled) {
            val used = prefs.getDailyOpensUsedToday()
            val max = prefs.dailyMaxOpens
            val mins = prefs.dailySessionMinutes
            b.tvDailyBudgetStatus.visibility = View.VISIBLE
            if (used >= max) {
                b.tvDailyBudgetStatus.text =
                    "⛔ Orçamento Diário Esgotado ($used/$max aberturas hoje)"
                b.tvDailyBudgetStatus.setTextColor(
                    ContextCompat.getColor(serviceContext, R.color.accent_red)
                )
            } else {
                b.tvDailyBudgetStatus.text =
                    "📊 Orçamento Diário: $used de $max aberturas usadas (${mins} min/sessão)"
                b.tvDailyBudgetStatus.setTextColor(
                    ContextCompat.getColor(serviceContext, R.color.accent_blue)
                )
            }
        } else {
            b.tvDailyBudgetStatus.visibility = View.GONE
        }

        if (pkg.isNotBlank()) {
            when {
                pkg.startsWith("Proteção Anti-Burla") -> {
                    b.tvBlockedAppName.text =
                        "Proteção Anti-Burla ativa: As configurações do FocusLock estão protegidas."
                }
                pkg.startsWith("Shorts / Reels") -> {
                    b.tvBlockedAppName.text =
                        "Feed curto (Shorts / Reels) bloqueado! Vídeos normais e mensagens continuam liberados."
                }
                else -> {
                    try {
                        val appInfo = serviceContext.packageManager.getApplicationInfo(pkg, 0)
                        val appLabel = serviceContext.packageManager.getApplicationLabel(appInfo).toString()
                        b.tvBlockedAppName.text =
                            "Mantenha o foco. O app \"$appLabel\" está restrito."
                    } catch (_: Exception) {
                        b.tvBlockedAppName.text =
                            "Mantenha o foco. Este aplicativo está restrito."
                    }
                }
            }
        }
    }

    private fun goToHome() {
        AppBlockerService.onNavigatedHome()

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Envia PendingIntent com permissão explícita de BAL no Android 14+ enquanto o Overlay ainda está visível
        try {
            val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getActivity(serviceContext, 2002, homeIntent, piFlags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                pi.send(serviceContext, 0, null, null, null, null, options.toBundle())
            } else {
                pi.send()
            }
        } catch (_: Exception) {
            try {
                serviceContext.startActivity(homeIntent)
            } catch (_: Exception) {
            }
        }

        dismiss()
    }

    private fun requestTransientAudioPause() {
        try {
            val am = serviceContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioPause() {
        try {
            val req = audioFocusRequest ?: return
            audioFocusRequest = null
            val am = serviceContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    private fun hideKeyboard(view: View) {
        try {
            val imm = serviceContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun vibrateSuccess() {
        try {
            val vibrator = serviceContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
        } catch (_: Exception) {
        }
    }

    private fun vibrateError() {
        try {
            val vibrator = serviceContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val pattern = longArrayOf(0, 100, 80, 100)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }
}
