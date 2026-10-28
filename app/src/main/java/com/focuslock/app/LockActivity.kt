package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.focuslock.app.databinding.ActivityLockBinding

class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

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
    }

    private fun updateBlockedAppLabel(intent: Intent?) {
        val pkg = intent?.getStringExtra("BLOCKED_PACKAGE")
        if (!pkg.isNullOrBlank()) {
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                binding.tvBlockedAppName.text = "Mantenha o foco. O app \"$appLabel\" está restrito."
            } catch (_: Exception) {
                binding.tvBlockedAppName.text = "Mantenha o foco. Este aplicativo está restrito."
            }
        }
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
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateBlockedAppLabel(intent)
        binding.tvError.visibility = View.GONE

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
            unlockSuccess("Desbloqueado com sucesso via Tag NFC!")
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
        val prefs = (application as FocusLockApp).prefs
        val hadActiveSchedule = prefs.getSchedules().any { it.isActiveNow() }
        prefs.isManualLockActive = false
        if (hadActiveSchedule) {
            prefs.snoozeSchedulesForMinutes(15)
        }

        isShowing = false
        vibrateSuccess()
        val finalMsg = if (hadActiveSchedule) {
            "$message (Horário pausado por 15 min)"
        } else {
            message
        }
        Toast.makeText(this, finalMsg, Toast.LENGTH_LONG).show()
        finish()
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
