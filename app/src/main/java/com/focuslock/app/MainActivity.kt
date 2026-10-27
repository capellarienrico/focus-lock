package com.focuslock.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.focuslock.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BlockPreferences
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var isWaitingForNfcRegistration = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as FocusLockApp).prefs
        setupNfc()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityPermission()
        updateUI()

        nfcAdapter?.let {
            val filter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
            it.enableForegroundDispatch(this, pendingIntent, arrayOf(filter), null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
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

        if (isWaitingForNfcRegistration) {
            prefs.registeredNfcTagId = tagId
            isWaitingForNfcRegistration = false
            Toast.makeText(this, "Tag NFC vinculada com sucesso! UID: $tagId", Toast.LENGTH_LONG).show()
            updateUI()
            return
        }

        val registered = prefs.registeredNfcTagId
        if (registered == null || registered == tagId) {
            // Alterna o estado de bloqueio
            prefs.isManualLockActive = !prefs.isManualLockActive
            val msg = if (prefs.isManualLockActive) "Modo Foco Ativado via NFC!" else "Modo Foco Desativado via NFC!"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            updateUI()
        } else {
            Toast.makeText(this, "Tag NFC não reconhecida.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        binding.btnToggleLock.setOnClickListener {
            if (prefs.isManualLockActive) {
                // Para desbloquear manualmente, EXIGE a Senha Mestre!
                promptPinToUnlock()
            } else {
                // Para bloquear, é direto
                prefs.isManualLockActive = true
                Toast.makeText(this, "Bloqueio ativado! Todos os apps bloqueados exceto WhatsApp.", Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }

        binding.btnRegisterNfc.setOnClickListener {
            if (prefs.registeredNfcTagId != null) {
                AlertDialog.Builder(this)
                    .setTitle("Tag NFC")
                    .setMessage("Tag atual: ${prefs.registeredNfcTagId}\nDeseja remover ou registrar outra?")
                    .setPositiveButton("Registrar Nova") { _, _ ->
                        isWaitingForNfcRegistration = true
                        Toast.makeText(this, "Aproxime a nova Tag NFC agora...", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Remover Vínculo") { _, _ ->
                        prefs.registeredNfcTagId = null
                        Toast.makeText(this, "Tag desvinculada.", Toast.LENGTH_SHORT).show()
                        updateUI()
                    }
                    .setNeutralButton("Cancelar", null)
                    .show()
            } else {
                isWaitingForNfcRegistration = true
                Toast.makeText(this, "Aproxime a Tag NFC do aparelho agora para cadastrar...", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnChangePin.setOnClickListener {
            promptChangePin()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Blocos de horário
        val schedules = prefs.getSchedules()
        if (schedules.isNotEmpty()) {
            val sched = schedules[0]
            binding.switchSchedule.isChecked = sched.isEnabled
            binding.switchSchedule.setOnCheckedChangeListener { _, isChecked ->
                val updated = schedules.toMutableList()
                updated[0] = sched.copy(isEnabled = isChecked)
                prefs.saveSchedules(updated)
                Toast.makeText(this, if (isChecked) "Bloco de Horário ativado!" else "Bloco de Horário desativado.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptPinToUnlock() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Digite o PIN Mestre"
            textSize = 18f
        }

        AlertDialog.Builder(this)
            .setTitle("Desbloqueio com Senha Mestre")
            .setMessage("Insira seu PIN para liberar os aplicativos sem a tag NFC:")
            .setView(input)
            .setPositiveButton("Desbloquear") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin == prefs.masterPin) {
                    prefs.isManualLockActive = false
                    Toast.makeText(this, "Desbloqueado com sucesso!", Toast.LENGTH_SHORT).show()
                    updateUI()
                } else {
                    Toast.makeText(this, "Senha incorreta!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptChangePin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Novo PIN (4 a 6 dígitos)"
        }

        AlertDialog.Builder(this)
            .setTitle("Alterar Senha Mestre")
            .setMessage("Digite o novo PIN:")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newPin = input.text.toString().trim()
                if (newPin.length >= 4) {
                    prefs.masterPin = newPin
                    Toast.makeText(this, "PIN Mestre alterado com sucesso!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "O PIN deve ter no mínimo 4 dígitos.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateUI() {
        val isLocked = prefs.isManualLockActive

        if (isLocked) {
            binding.ivLockStatus.setImageResource(R.drawable.ic_lock)
            binding.ivLockStatus.setColorFilter(ContextCompat.getColor(this, R.color.accent_red))
            binding.tvLockStatus.text = "STATUS: BLOQUEADO"
            binding.tvLockStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.tvLockSubtitle.text = "Todos os apps bloqueados (exceto WhatsApp)"
            binding.btnToggleLock.text = "DESBLOQUEAR (EXIGE PIN)"
            binding.btnToggleLock.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))
        } else {
            binding.ivLockStatus.setImageResource(R.drawable.ic_focus_shield)
            binding.ivLockStatus.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvLockStatus.text = "STATUS: LIVRE"
            binding.tvLockStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvLockSubtitle.text = "Todos os aplicativos liberados"
            binding.btnToggleLock.text = "ATIVAR BLOQUEIO AGORA"
            binding.btnToggleLock.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
        }

        val registered = prefs.registeredNfcTagId
        if (registered != null) {
            binding.tvNfcInfo.text = "Tag NFC Vinculada: $registered\n(Aproxime esta tag para alternar o bloqueio)"
        } else {
            binding.tvNfcInfo.text = "Nenhuma Tag NFC vinculada. Toque no botão abaixo e aproxime sua tag para cadastrar."
        }
    }

    private fun checkAccessibilityPermission() {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val isServiceEnabled = enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }

        if (isServiceEnabled) {
            binding.cardAccessibilityWarning.visibility = View.GONE
        } else {
            binding.cardAccessibilityWarning.visibility = View.VISIBLE
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
