package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.focuslock.app.databinding.ActivityLockBinding

class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var mindfulTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenFlags()
        updateBlockedAppLabel(intent)

        // Botão voltar redireciona para a Home do Android
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goToHome()
            }
        })

        binding.btnGoHome.setOnClickListener {
            goToHome()
        }

        setupNfc()
        setupPinUnlock()
        applyStrictnessModeUI()
    }

    private fun updateBlockedAppLabel(intent: Intent?) {
        val prefs = (application as FocusLockApp).prefs
        val attempts = prefs.getBlockedAttemptsToday()
        binding.tvBlockedStats.text = "🛡️ $attempts tentativa(s) de distração bloqueada(s) hoje"

        val pkg = intent?.getStringExtra("BLOCKED_PACKAGE")
        if (!pkg.isNullOrBlank()) {
            if (pkg.startsWith("Proteção Anti-Burla")) {
                binding.tvBlockedAppName.text = "Proteção Anti-Burla ativa: As configurações do FocusLock estão protegidas."
                return
            }
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                binding.tvBlockedAppName.text = "Mantenha o foco. O app \"$appLabel\" está restrito."
            } catch (_: Exception) {
                binding.tvBlockedAppName.text = "Mantenha o foco. Este aplicativo está restrito."
            }
        }
    }

    private fun applyStrictnessModeUI() {
        mindfulTimer?.cancel()
        val prefs = (application as FocusLockApp).prefs
        val mode = prefs.strictnessMode

        when (mode) {
            BlockPreferences.MODE_DEEP_FOCUS_NFC -> {
                binding.llMindfulSection.visibility = View.VISIBLE
                binding.llPinSection.visibility = View.GONE
                binding.tvMindfulPrompt.text =
                    "🔒 Modo Deep Focus (Opal / Brick)\nDesbloqueio prioritário via Tag NFC física."
                binding.tvMindfulCountdown.visibility = View.GONE
                binding.btnEmergencyPinUnlock.visibility = View.VISIBLE
                binding.btnEmergencyPinUnlock.setOnClickListener {
                    binding.btnEmergencyPinUnlock.visibility = View.GONE
                    binding.tvMindfulCountdown.visibility = View.VISIBLE
                    startCountdownToRevealPin(30)
                }
            }
            BlockPreferences.MODE_MINDFUL_DELAY -> {
                binding.llMindfulSection.visibility = View.VISIBLE
                binding.llPinSection.visibility = View.GONE
                binding.tvMindfulPrompt.text =
                    "🧘 Pausa Consciente (ScreenZen)\nRespire fundo. Você realmente precisa abrir este app agora?"
                binding.tvMindfulCountdown.visibility = View.VISIBLE
                binding.btnEmergencyPinUnlock.visibility = View.GONE
                startCountdownToRevealPin(15)
            }
            else -> {
                binding.llMindfulSection.visibility = View.GONE
                binding.llPinSection.visibility = View.VISIBLE
            }
        }
    }

    private fun startCountdownToRevealPin(seconds: Int) {
        mindfulTimer?.cancel()
        mindfulTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L) + 1
                binding.tvMindfulCountdown.text = "Liberando campo de Senha Mestre em ${secLeft}s..."
            }

            override fun onFinish() {
                binding.llMindfulSection.visibility = View.GONE
                binding.llPinSection.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun setupScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun goToHome() {
        isShowing = false
        mindfulTimer?.cancel()
        val switchedByService = AppBlockerAccessibilityService.instance
            ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true

        if (!switchedByService) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }
        finish()
    }

    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        isShowing = false
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        isShowing = false
        mindfulTimer?.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateBlockedAppLabel(intent)
        binding.tvError.visibility = View.GONE
        applyStrictnessModeUI()

        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag != null) {
            handleNfcTag(tag)
        }
    }

    private fun handleNfcTag(tag: Tag) {
        val tagId = bytesToHex(tag.id)
        val prefs = (application as FocusLockApp).prefs
        val registeredTag = prefs.registeredNfcTagId

        if (registeredTag == null || registeredTag == tagId) {
            unlockSuccess("Desbloqueado via Tag NFC!")
        } else {
            vibrateError()
            binding.tvError.text = "Tag NFC não reconhecida para desbloqueio."
            binding.tvError.visibility = View.VISIBLE
        }
    }

    private fun setupPinUnlock() {
        binding.btnUnlockWithPin.setOnClickListener {
            val enteredPin = binding.etPin.text?.toString()?.trim() ?: ""
            val masterPin = (application as FocusLockApp).prefs.masterPin

            if (enteredPin.isNotEmpty() && enteredPin == masterPin) {
                unlockSuccess("Desbloqueado com Senha Mestre!")
            } else {
                vibrateError()
                binding.tvError.text = "Senha Mestre incorreta. Tente novamente."
                binding.tvError.visibility = View.VISIBLE
                binding.etPin.text?.clear()
            }
        }
    }

    private fun unlockSuccess(message: String) {
        mindfulTimer?.cancel()
        val prefs = (application as FocusLockApp).prefs
        val hadActiveSchedule = prefs.getSchedules().any { it.isActiveNow() }
        prefs.isManualLockActive = false

        vibrateSuccess()

        if (hadActiveSchedule) {
            val options = arrayOf(
                "1 minuto (Olhada rápida - ScreenZen)",
                "5 minutos",
                "15 minutos",
                "30 minutos"
            )
            val minutesValues = intArrayOf(1, 5, 15, 30)

            AlertDialog.Builder(this)
                .setTitle("Por quanto tempo deseja liberar?")
                .setCancelable(false)
                .setItems(options) { _, which ->
                    val mins = minutesValues[which]
                    prefs.snoozeSchedulesForMinutes(mins)
                    isShowing = false
                    Toast.makeText(
                        this,
                        "$message (Liberado por $mins min)",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                .show()
        } else {
            isShowing = false
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun vibrateSuccess() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    private fun vibrateError() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 100, 80, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X:", b))
        }
        return if (sb.isNotEmpty()) sb.substring(0, sb.length - 1) else ""
    }

    companion object {
        @Volatile
        var isShowing: Boolean = false
    }
}
