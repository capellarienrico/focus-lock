package com.focuslock.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
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

        // Impede que o usuário feche a tela de bloqueio com o botão voltar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            }
        })

        setupNfc()
        setupPinUnlock()
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
        nfcAdapter?.let {
            val filter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
            it.enableForegroundDispatch(this, pendingIntent, arrayOf(filter), null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action
        ) {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            tag?.let { handleNfcTag(it) }
        }
    }

    private fun handleNfcTag(tag: Tag) {
        val tagId = bytesToHex(tag.id)
        val prefs = (application as FocusLockApp).prefs
        val registeredTag = prefs.registeredNfcTagId

        // Se uma tag específica estiver cadastrada, valida se é ela; senão, qualquer tag registrada libera
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
            val enteredPin = binding.etPin.text.toString().trim()
            val masterPin = (application as FocusLockApp).prefs.masterPin

            if (enteredPin == masterPin) {
                unlockSuccess("Desbloqueado com Senha Mestre!")
            } else {
                vibrateError()
                binding.tvError.text = "Senha incorreta. Tente novamente."
                binding.tvError.visibility = View.VISIBLE
                binding.etPin.text?.clear()
            }
        }
    }

    private fun unlockSuccess(message: String) {
        val prefs = (application as FocusLockApp).prefs
        prefs.isManualLockActive = false

        vibrateSuccess()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
}
