package com.focuslock.app

import android.Manifest
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.focuslock.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import java.security.SecureRandom
import java.util.Calendar
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BlockPreferences
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var isWaitingForNfcRegistration = false
    private var isFirstSetupDialogShowing = false
    private var hasPromptedNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as FocusLockApp).prefs
        setupTabs()
        setupNfc()
        setupListeners()

        if (savedInstanceState == null) {
            extractAndHandleNfcIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        checkFirstTimePasswordSetup()
        requestNotificationPermissionIfNeeded()
        checkPermissionsStatus()
        checkNfcStatus()
        updateUI()

        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (hasPromptedNotifications || !prefs.isMasterPasswordConfigured) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                hasPromptedNotifications = true
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1005
                )
            }
        }
    }

    private fun checkFirstTimePasswordSetup() {
        if (prefs.isMasterPasswordConfigured || isFirstSetupDialogShowing) {
            return
        }
        isFirstSetupDialogShowing = true

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, dpToPx(4))
        }

        val infoText = TextView(this).apply {
            text = "Bem-vindo ao FocusLock! Antes de começar, defina sua Senha Mestre alfanumérica (pode conter letras, números e símbolos, mínimo de 4 caracteres)."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
        }

        val inputPass = EditText(this).apply {
            hint = "Crie sua Senha Mestre (ex: Foco2026)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

        val inputConfirm = EditText(this).apply {
            hint = "Confirme a Senha Mestre"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

        val cbShowPass = CheckBox(this).apply {
            text = "Mostrar senhas"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setOnCheckedChangeListener { _, isChecked ->
                val method = if (isChecked) {
                    HideReturnsTransformationMethod.getInstance()
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                inputPass.transformationMethod = method
                inputConfirm.transformationMethod = method
                inputPass.setSelection(inputPass.text?.length ?: 0)
                inputConfirm.setSelection(inputConfirm.text?.length ?: 0)
            }
        }

        val btnGenerateFirstPass = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Gerar Senha Aleatória Complexa e Copiar"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
            textSize = 12f
            setOnClickListener {
                val generated = generateComplexPassword(16)
                inputPass.setText(generated)
                inputConfirm.setText(generated)
                cbShowPass.isChecked = true
                copyPasswordToClipboard(generated)
            }
        }

        container.addView(infoText)
        container.addView(inputPass)
        container.addView(inputConfirm)
        container.addView(cbShowPass)
        container.addView(btnGenerateFirstPass)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Configurar Senha Mestre")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Salvar Senha", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = inputPass.text?.toString()?.trim() ?: ""
                val p2 = inputConfirm.text?.toString()?.trim() ?: ""

                when {
                    p1.length < 4 -> {
                        inputPass.error = "A senha deve ter pelo menos 4 caracteres"
                    }
                    p1 != p2 -> {
                        inputConfirm.error = "As senhas não coincidem"
                    }
                    else -> {
                        prefs.masterPin = p1
                        isFirstSetupDialogShowing = false
                        Toast.makeText(this, "Senha Mestre configurada com sucesso!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        requestNotificationPermissionIfNeeded()
                        updateUI()
                    }
                }
            }
        }

        dialog.show()
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

    private fun checkNfcStatus() {
        nfcAdapter?.let {
            if (!it.isEnabled) {
                Toast.makeText(this, "NFC desligado. Ative nas configurações para usar sua tag.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractAndHandleNfcIntent(intent)
    }

    private fun extractAndHandleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let { handleNfcTag(it) }
    }

    private var pendingNfcTagName: String = "Tag de Casa"

    private fun handleNfcTag(tag: Tag) {
        val tagId = bytesToHex(tag.id)

        if (isWaitingForNfcRegistration) {
            isWaitingForNfcRegistration = false
            val currentTags = prefs.getNfcTags().filterNot { it.uid.equals(tagId, ignoreCase = true) }.toMutableList()
            currentTags.add(NfcTagProfile(uid = tagId, name = pendingNfcTagName))
            prefs.saveNfcTags(currentTags)
            Toast.makeText(
                this,
                "Tag \"$pendingNfcTagName\" vinculada! UID: $tagId",
                Toast.LENGTH_LONG
            ).show()
            updateUI()
            return
        }

        if (prefs.isNfcTagAllowed(tagId)) {
            val tagName = prefs.findNfcTagName(tagId) ?: "Tag NFC"
            if (prefs.isBlockingCurrentlyActive()) {
                val hadSchedule = prefs.isAnyScheduleActiveNow()
                prefs.isManualLockActive = false
                if (hadSchedule) {
                    prefs.snoozeSchedulesForMinutes(15)
                }
                AppBlockerService.onUnlocked()
                Toast.makeText(this, "Modo Foco Desativado via $tagName!", Toast.LENGTH_SHORT).show()
            } else {
                if (!ensureAccessibilityBeforeLock()) return
                prefs.isManualLockActive = true
                AppBlockerService.ensureRunning(this)
                AppBlockerService.notifyStateChanged()
                Toast.makeText(this, "Modo Foco Ativado via $tagName!", Toast.LENGTH_SHORT).show()
            }
            updateUI()
        } else {
            Toast.makeText(this, "Tag NFC ($tagId) não reconhecida.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTabs() {
        binding.tabLayoutMain.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position ?: 0
                binding.llTabFocus.visibility = if (pos == 0) View.VISIBLE else View.GONE
                binding.llTabSchedules.visibility = if (pos == 1) View.VISIBLE else View.GONE
                binding.llTabStrictness.visibility = if (pos == 2) View.VISIBLE else View.GONE
                binding.llTabSettings.visibility = if (pos == 3) View.VISIBLE else View.GONE
                binding.scrollViewMain.scrollTo(0, 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {
                binding.scrollViewMain.smoothScrollTo(0, 0)
            }
        })
    }

    private fun ensureAccessibilityBeforeLock(): Boolean {
        if (!AppBlockerService.isServiceEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permissão de Acesso ao Uso Necessária")
                .setMessage(
                    "Para que o FocusLock identifique qual app está aberto (sem usar Acessibilidade e sem conflitar com o Nubank/bancos), ative o \"FocusLock\" em Acesso ao Uso."
                )
                .setPositiveButton("Abrir Acesso ao Uso") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return false
        }

        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permissão de Sobreposição Necessária")
                .setMessage(
                    "Ative a permissão \"Sobrepor a outros apps\" para que a tela de bloqueio possa aparecer imediatamente quando um app restrito for aberto."
                )
                .setPositiveButton("Permitir Sobreposição") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return false
        }

        AppBlockerService.ensureRunning(this)
        return true
    }

    private fun setupListeners() {
        binding.btnToggleLock.setOnClickListener {
            if (prefs.isBlockingCurrentlyActive()) {
                promptPinToUnlock()
            } else {
                if (!ensureAccessibilityBeforeLock()) return@setOnClickListener
                prefs.isManualLockActive = true
                AppBlockerService.ensureRunning(this)
                AppBlockerService.notifyStateChanged()
                Toast.makeText(this, "Bloqueio ativado com sucesso!", Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }

        binding.btnRegisterNfc.setOnClickListener {
            if (nfcAdapter == null) {
                Toast.makeText(this, "Este aparelho não possui leitor NFC.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!nfcAdapter!!.isEnabled) {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                return@setOnClickListener
            }

            if (prefs.isBlockingCurrentlyActive()) {
                verifyPasswordThen("Insira a Senha Mestre para gerenciar suas Tags NFC durante o bloqueio:") {
                    showManageNfcTagsDialog()
                }
            } else {
                showManageNfcTagsDialog()
            }
        }

        binding.btnChangePin.setOnClickListener {
            promptChangePin()
        }

        binding.btnResetRandomPin.setOnClickListener {
            showRandomPasswordResetDialog()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        binding.btnEnableOverlay.setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        binding.btnDisableBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.btnAddSchedule.setOnClickListener {
            showAddOrEditScheduleDialog(null)
        }

        binding.btnSelectApps.setOnClickListener {
            if (prefs.isBlockingCurrentlyActive()) {
                verifyPasswordThen("Insira a Senha Mestre para alterar a lista de apps bloqueados:") {
                    showSelectAppsDialog()
                }
            } else {
                showSelectAppsDialog()
            }
        }

        binding.btnChangeStrictnessMode.setOnClickListener {
            if (prefs.isBlockingCurrentlyActive()) {
                verifyPasswordThen("Insira a Senha Mestre para alterar o Nível de Rigor durante o bloqueio:") {
                    showStrictnessModeDialog()
                }
            } else {
                showStrictnessModeDialog()
            }
        }

        binding.switchAntiTamper.setOnClickListener {
            val target = binding.switchAntiTamper.isChecked
            if (!target && prefs.isBlockingCurrentlyActive()) {
                binding.switchAntiTamper.isChecked = true
                verifyPasswordThen("Insira a Senha Mestre para desativar a Proteção Anti-Burla:") {
                    prefs.isAntiTamperEnabled = false
                    updateUI()
                    Toast.makeText(this, "Proteção Anti-Burla desativada.", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.isAntiTamperEnabled = target
                Toast.makeText(
                    this,
                    if (target) "Proteção Anti-Burla ativada!" else "Proteção Anti-Burla desativada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.switchCognitiveChallenge.setOnClickListener {
            val target = binding.switchCognitiveChallenge.isChecked
            if (!target && prefs.isBlockingCurrentlyActive()) {
                binding.switchCognitiveChallenge.isChecked = true
                verifyPasswordThen("Insira a Senha Mestre para desativar o Desafio Cognitivo:") {
                    prefs.isCognitiveChallengeEnabled = false
                    updateUI()
                    Toast.makeText(this, "Desafio Cognitivo desativado.", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.isCognitiveChallengeEnabled = target
                Toast.makeText(
                    this,
                    if (target) "Desafio Cognitivo ativado!" else "Desafio Cognitivo desativado.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnEditCustomQuote.setOnClickListener {
            showEditCustomQuoteDialog()
        }

        binding.switchDailyBudget.setOnClickListener {
            val target = binding.switchDailyBudget.isChecked
            if (!target && prefs.isBlockingCurrentlyActive()) {
                binding.switchDailyBudget.isChecked = true
                verifyPasswordThen("Insira a Senha Mestre para desativar o Orçamento Diário:") {
                    prefs.isDailyBudgetEnabled = false
                    updateUI()
                    Toast.makeText(this, "Orçamento Diário desativado.", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.isDailyBudgetEnabled = target
                updateUI()
                Toast.makeText(
                    this,
                    if (target) "Orçamento Diário ativado!" else "Orçamento Diário desativado.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnConfigureDailyBudget.setOnClickListener {
            if (prefs.isBlockingCurrentlyActive()) {
                verifyPasswordThen("Insira a Senha Mestre para alterar o Orçamento Diário:") {
                    showConfigureDailyBudgetDialog()
                }
            } else {
                showConfigureDailyBudgetDialog()
            }
        }
    }

    private fun showManageNfcTagsDialog() {
        val tags = prefs.getNfcTags()
        if (tags.isEmpty()) {
            promptNewNfcTagName()
            return
        }

        val items = tags.map { "🏷️ ${it.name} (${it.uid}) — Toque para remover" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Gerenciar Tags NFC (${tags.size})")
            .setItems(items) { _, which ->
                val removed = tags[which]
                val updated = tags.filterIndexed { idx, _ -> idx != which }
                prefs.saveNfcTags(updated)
                Toast.makeText(this, "Tag \"${removed.name}\" removida.", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setPositiveButton("+ Adicionar Nova Tag") { _, _ ->
                promptNewNfcTagName()
            }
            .setNeutralButton("Limpar Todas") { _, _ ->
                prefs.saveNfcTags(emptyList())
                Toast.makeText(this, "Todas as tags foram desvinculadas.", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun promptNewNfcTagName() {
        val input = EditText(this).apply {
            hint = "Nome da Tag (ex: Casa, Trabalho, Quarto)"
            setText("Tag de Casa")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, dpToPx(4))
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Cadastrar Nova Tag NFC")
            .setMessage("Escolha um nome para identificar esta Tag NFC e depois aproxime-a do celular:")
            .setView(container)
            .setPositiveButton("Aproximar Tag Agora") { _, _ ->
                pendingNfcTagName = input.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Tag NFC"
                isWaitingForNfcRegistration = true
                Toast.makeText(
                    this,
                    "Aproxime a tag \"$pendingNfcTagName\" na parte traseira do aparelho agora...",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showEditCustomQuoteDialog() {
        val input = EditText(this).apply {
            hint = "Ex: Foco no projeto até às 18h — não abra o feed!"
            setText(prefs.customLockQuote)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, dpToPx(4))
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Frase / Meta na Tela de Bloqueio")
            .setMessage("Esta mensagem aparecerá em destaque toda vez que um aplicativo restrito for bloqueado:")
            .setView(container)
            .setPositiveButton("Salvar") { _, _ ->
                val quote = input.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                    ?: BlockPreferences.DEFAULT_LOCK_QUOTE
                prefs.customLockQuote = quote
                updateUI()
                Toast.makeText(this, "Frase personalizada atualizada!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Restaurar Padrão") { _, _ ->
                prefs.customLockQuote = BlockPreferences.DEFAULT_LOCK_QUOTE
                updateUI()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showConfigureDailyBudgetDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, dpToPx(4))
        }

        val lblOpens = TextView(this).apply {
            text = "Máximo de aberturas permitidas por dia:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            textSize = 13f
        }
        val inputOpens = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.dailyMaxOpens.toString())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
        }

        val lblMinutes = TextView(this).apply {
            text = "Minutos liberados por cada abertura:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            textSize = 13f
            setPadding(0, dpToPx(10), 0, 0)
        }
        val inputMinutes = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.dailySessionMinutes.toString())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
        }

        container.addView(lblOpens)
        container.addView(inputOpens)
        container.addView(lblMinutes)
        container.addView(inputMinutes)

        AlertDialog.Builder(this)
            .setTitle("Orçamento Diário (ScreenZen)")
            .setView(container)
            .setPositiveButton("Salvar") { _, _ ->
                val maxOpens = inputOpens.text?.toString()?.toIntOrNull()?.coerceIn(1, 100) ?: 5
                val sessionMins = inputMinutes.text?.toString()?.toIntOrNull()?.coerceIn(1, 120) ?: 5
                prefs.dailyMaxOpens = maxOpens
                prefs.dailySessionMinutes = sessionMins
                prefs.isDailyBudgetEnabled = true
                updateUI()
                Toast.makeText(
                    this,
                    "Orçamento configurado: $maxOpens aberturas/dia de $sessionMins min cada!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showStrictnessModeDialog() {
        val modes = arrayOf(
            "Normal (Senha Mestre ou NFC imediato)",
            "Pausa Consciente — ScreenZen (Espera de 15s antes da senha)",
            "Deep Focus — Opal / Brick (Exclusivo Tag NFC + Emergência 30s)"
        )
        val modeValues = arrayOf(
            BlockPreferences.MODE_NORMAL,
            BlockPreferences.MODE_MINDFUL_DELAY,
            BlockPreferences.MODE_DEEP_FOCUS_NFC
        )
        val currentIdx = modeValues.indexOf(prefs.strictnessMode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Escolher Nível de Rigor")
            .setSingleChoiceItems(modes, currentIdx) { dialog, which ->
                prefs.strictnessMode = modeValues[which]
                updateUI()
                Toast.makeText(this, "Modo atualizado: ${modes[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun verifyPasswordThen(message: String, onVerified: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(8), pad, 0)
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Digite a Senha Mestre"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

        val cbShow = CheckBox(this).apply {
            text = "Mostrar senha"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setOnCheckedChangeListener { _, isChecked ->
                input.transformationMethod = if (isChecked) {
                    HideReturnsTransformationMethod.getInstance()
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                input.setSelection(input.text?.length ?: 0)
            }
        }

        container.addView(input)
        container.addView(cbShow)

        AlertDialog.Builder(this)
            .setTitle("Confirmação de Segurança")
            .setMessage(message)
            .setView(container)
            .setPositiveButton("Confirmar") { _, _ ->
                val entered = input.text?.toString()?.trim() ?: ""
                if (entered == prefs.masterPin) {
                    onVerified()
                } else {
                    Toast.makeText(this, "Senha Mestre incorreta!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renderSchedulesList() {
        binding.llSchedulesContainer.removeAllViews()
        val schedules = prefs.getSchedules()

        if (schedules.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Nenhum bloco de horário configurado. Toque em \"+ Novo Horário\" para criar."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
                setPadding(0, dpToPx(8), 0, dpToPx(8))
            }
            binding.llSchedulesContainer.addView(emptyTv)
            return
        }

        schedules.forEachIndexed { index, schedule ->
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val pad = dpToPx(12)
                setPadding(pad, pad, pad, pad)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dpToPx(8)
                }
                layoutParams = params
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val isCurrentlyActive = schedule.isActiveNow() && !prefs.isScheduleSnoozed()
            val titleTv = TextView(this).apply {
                text = "${schedule.name} • ${schedule.formatTimeRange()}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            }

            val subtitleTv = TextView(this).apply {
                val statusBadge = if (isCurrentlyActive) " • ATIVO AGORA" else ""
                val appsBadge = if (schedule.customBlockedPackages.isEmpty()) {
                    "Apps: Lista Global"
                } else {
                    "Apps: ${schedule.customBlockedPackages.size} específico(s)"
                }
                text = "Dias: ${schedule.formatDaysOfWeek()} • $appsBadge$statusBadge"
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isCurrentlyActive) R.color.accent_green else R.color.text_secondary
                    )
                )
                textSize = 12f
            }

            textContainer.addView(titleTv)
            textContainer.addView(subtitleTv)

            val toggle = SwitchCompat(this).apply {
                isChecked = schedule.isEnabled
                setOnClickListener {
                    val targetState = isChecked
                    if (!targetState && schedule.isActiveNow() && !prefs.isScheduleSnoozed()) {
                        // Reverte visualmente até digitar a senha
                        isChecked = true
                        verifyPasswordThen("Este bloco está ativo agora. Digite a Senha Mestre para desativá-lo:") {
                            updateScheduleEnabled(schedule.id, false)
                        }
                    } else {
                        if (targetState && !ensureAccessibilityBeforeLock()) {
                            isChecked = false
                            return@setOnClickListener
                        }
                        updateScheduleEnabled(schedule.id, targetState)
                    }
                }
            }

            topRow.addView(textContainer)
            topRow.addView(toggle)

            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(6)
                }
                layoutParams = params
            }

            val btnEdit = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "Editar"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
                textSize = 12f
                minHeight = dpToPx(36)
                minimumHeight = dpToPx(36)
                setOnClickListener {
                    if (schedule.isActiveNow() && !prefs.isScheduleSnoozed()) {
                        verifyPasswordThen("Digite a Senha Mestre para editar um horário em andamento:") {
                            showAddOrEditScheduleDialog(schedule)
                        }
                    } else {
                        showAddOrEditScheduleDialog(schedule)
                    }
                }
            }

            val btnDelete = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "Excluir"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
                textSize = 12f
                minHeight = dpToPx(36)
                minimumHeight = dpToPx(36)
                setOnClickListener {
                    val deleteAction = {
                        val updated = prefs.getSchedules().filterNot { it.id == schedule.id }
                        prefs.saveSchedules(updated)
                        Toast.makeText(this@MainActivity, "Horário removido.", Toast.LENGTH_SHORT).show()
                        updateUI()
                    }
                    if (schedule.isActiveNow() && !prefs.isScheduleSnoozed()) {
                        verifyPasswordThen("Digite a Senha Mestre para excluir um horário em andamento:") {
                            deleteAction()
                        }
                    } else {
                        deleteAction()
                    }
                }
            }

            actionsRow.addView(btnEdit)
            actionsRow.addView(btnDelete)

            itemCard.addView(topRow)
            itemCard.addView(actionsRow)

            binding.llSchedulesContainer.addView(itemCard)
        }
    }

    private fun updateScheduleEnabled(scheduleId: String, enabled: Boolean) {
        val list = prefs.getSchedules()
        val idx = list.indexOfFirst { it.id == scheduleId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(isEnabled = enabled)
            if (enabled) {
                prefs.scheduleSnoozeUntilMillis = 0L
            }
            prefs.saveSchedules(list)
            AppBlockerService.ensureRunning(this)
            AppBlockerService.notifyStateChanged()
            Toast.makeText(
                this,
                if (enabled) "Bloco de horário ativado!" else "Bloco de horário desativado.",
                Toast.LENGTH_SHORT
            ).show()
            updateUI()
        }
    }

    private fun showAddOrEditScheduleDialog(existing: BlockSchedule?) {
        var selectedStartHour = existing?.startHour ?: 9
        var selectedStartMinute = existing?.startMinute ?: 0
        var selectedEndHour = existing?.endHour ?: 18
        var selectedEndMinute = existing?.endMinute ?: 0
        val selectedDays = (existing?.daysOfWeek ?: listOf(1, 2, 3, 4, 5, 6, 7)).toMutableSet()
        val selectedCustomApps = (existing?.customBlockedPackages ?: emptySet()).toMutableSet()

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, dpToPx(8))
        }
        scrollView.addView(container)

        val nameInput = EditText(this).apply {
            hint = "Nome do bloco (ex: Trabalho, Estudos, Noite)"
            setText(existing?.name ?: "Bloco de Foco")
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        container.addView(nameInput)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
            layoutParams = params
        }

        val btnStartTime = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(6)
            }
            text = String.format("Início: %02d:%02d", selectedStartHour, selectedStartMinute)
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    selectedStartHour = h
                    selectedStartMinute = m
                    text = String.format("Início: %02d:%02d", h, m)
                }, selectedStartHour, selectedStartMinute, true).show()
            }
        }

        val btnEndTime = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = String.format("Fim: %02d:%02d", selectedEndHour, selectedEndMinute)
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    selectedEndHour = h
                    selectedEndMinute = m
                    text = String.format("Fim: %02d:%02d", h, m)
                }, selectedEndHour, selectedEndMinute, true).show()
            }
        }

        timeRow.addView(btnStartTime)
        timeRow.addView(btnEndTime)
        container.addView(timeRow)

        val btnScheduleApps = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
            text = if (selectedCustomApps.isEmpty()) {
                "Apps deste Horário: Lista Global"
            } else {
                "Apps deste Horário: ${selectedCustomApps.size} selecionado(s)"
            }
            setOnClickListener {
                showScheduleCustomAppsPicker(selectedCustomApps) {
                    text = if (selectedCustomApps.isEmpty()) {
                        "Apps deste Horário: Lista Global"
                    } else {
                        "Apps deste Horário: ${selectedCustomApps.size} selecionado(s)"
                    }
                }
            }
        }
        container.addView(btnScheduleApps)

        val daysLabel = TextView(this).apply {
            text = "Dias da semana ativos:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            textSize = 13f
            setPadding(0, dpToPx(12), 0, dpToPx(4))
        }
        container.addView(daysLabel)

        val dayOptions = listOf(
            Calendar.MONDAY to "Segunda-feira",
            Calendar.TUESDAY to "Terça-feira",
            Calendar.WEDNESDAY to "Quarta-feira",
            Calendar.THURSDAY to "Quinta-feira",
            Calendar.FRIDAY to "Sexta-feira",
            Calendar.SATURDAY to "Sábado",
            Calendar.SUNDAY to "Domingo"
        )

        dayOptions.forEach { (dayConst, dayName) ->
            val cb = CheckBox(this).apply {
                text = dayName
                isChecked = selectedDays.contains(dayConst)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedDays.add(dayConst) else selectedDays.remove(dayConst)
                }
            }
            container.addView(cb)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Novo Bloco de Horário" else "Editar Bloco de Horário")
            .setView(scrollView)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Bloco de Foco"
                if (selectedDays.isEmpty()) {
                    Toast.makeText(this, "Selecione pelo menos um dia da semana.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val newSchedule = BlockSchedule(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    startHour = selectedStartHour,
                    startMinute = selectedStartMinute,
                    endHour = selectedEndHour,
                    endMinute = selectedEndMinute,
                    daysOfWeek = selectedDays.sorted(),
                    isEnabled = existing?.isEnabled ?: true,
                    customBlockedPackages = selectedCustomApps.toSet()
                )

                val currentList = prefs.getSchedules()
                val existingIndex = currentList.indexOfFirst { it.id == newSchedule.id }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = newSchedule
                } else {
                    currentList.add(newSchedule)
                }
                prefs.scheduleSnoozeUntilMillis = 0L
                prefs.saveSchedules(currentList)
                AppBlockerService.notifyStateChanged()
                Toast.makeText(this, "Bloco de horário salvo!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                updateUI()

                if (newSchedule.isEnabled) {
                    ensureAccessibilityBeforeLock()
                }
            }
        }

        dialog.show()
    }

    private fun showScheduleCustomAppsPicker(targetSet: MutableSet<String>, onUpdated: () -> Unit) {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launchables = pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { app -> !prefs.isAlwaysAllowed(app.packageName) }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        if (launchables.isEmpty()) {
            Toast.makeText(this, "Nenhum aplicativo encontrado.", Toast.LENGTH_SHORT).show()
            return
        }

        val appNames = launchables.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val tempSet = targetSet.toMutableSet()
        val checkedItems = launchables.map { tempSet.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Apps Específicos deste Horário")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = launchables[which].packageName
                if (isChecked) tempSet.add(pkg) else tempSet.remove(pkg)
            }
            .setPositiveButton("Confirmar") { _, _ ->
                targetSet.clear()
                targetSet.addAll(tempSet)
                onUpdated()
            }
            .setNeutralButton("Usar Lista Global") { _, _ ->
                targetSet.clear()
                onUpdated()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSelectAppsDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launchables = pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { app -> !prefs.isAlwaysAllowed(app.packageName) }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        if (launchables.isEmpty()) {
            Toast.makeText(this, "Nenhum aplicativo bloqueável encontrado.", Toast.LENGTH_SHORT).show()
            return
        }

        val appNames = launchables.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val currentBlocked = prefs.getBlockedPackages().toMutableSet()
        val checkedItems = launchables.map { currentBlocked.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Escolher Apps para Bloquear")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = launchables[which].packageName
                if (isChecked) {
                    currentBlocked.add(pkg)
                } else {
                    currentBlocked.remove(pkg)
                }
            }
            .setPositiveButton("Salvar Seleção") { _, _ ->
                prefs.saveBlockedPackages(currentBlocked)
                val count = currentBlocked.size
                val msg = if (count == 0) {
                    "Configurado para bloquear TODOS os apps (exceto WhatsApp)."
                } else {
                    "$count aplicativo(s) selecionado(s) para bloqueio."
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNeutralButton("Bloquear Todos") { _, _ ->
                prefs.saveBlockedPackages(emptySet())
                Toast.makeText(this, "Todos os apps (exceto WhatsApp) serão bloqueados.", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptPinToUnlock() {
        verifyPasswordThen("Insira sua Senha Mestre alfanumérica para liberar os aplicativos:") {
            val hadSchedule = prefs.isAnyScheduleActiveNow()
            prefs.isManualLockActive = false
            if (hadSchedule) {
                val options = arrayOf(
                    "1 minuto (Olhada rápida - ScreenZen)",
                    "5 minutos",
                    "15 minutos",
                    "30 minutos"
                )
                val minutesValues = intArrayOf(1, 5, 15, 30)
                AlertDialog.Builder(this)
                    .setTitle("Por quanto tempo deseja pausar os horários?")
                    .setItems(options) { _, which ->
                        val mins = minutesValues[which]
                        prefs.snoozeSchedulesForMinutes(mins)
                        AppBlockerService.onUnlocked()
                        Toast.makeText(
                            this,
                            "Desbloqueado! (Horários pausados por $mins min)",
                            Toast.LENGTH_LONG
                        ).show()
                        updateUI()
                    }
                    .show()
            } else {
                AppBlockerService.onUnlocked()
                Toast.makeText(this, "Desbloqueado com sucesso!", Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }
    }

    private fun promptChangePin() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, dpToPx(4))
        }

        val inputCurrent = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Senha Mestre Atual"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

        val inputNew = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Nova Senha Mestre (letras e números)"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

        val inputConfirm = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Confirmar Nova Senha"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_white))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

        val cbShow = CheckBox(this).apply {
            text = "Mostrar senhas"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setOnCheckedChangeListener { _, isChecked ->
                val method = if (isChecked) {
                    HideReturnsTransformationMethod.getInstance()
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                inputCurrent.transformationMethod = method
                inputNew.transformationMethod = method
                inputConfirm.transformationMethod = method
            }
        }

        container.addView(inputCurrent)
        container.addView(inputNew)
        container.addView(inputConfirm)
        container.addView(cbShow)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Alterar Senha Mestre")
            .setView(container)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val curr = inputCurrent.text?.toString()?.trim() ?: ""
                val newPass = inputNew.text?.toString()?.trim() ?: ""
                val confirmPass = inputConfirm.text?.toString()?.trim() ?: ""

                when {
                    curr != prefs.masterPin -> {
                        inputCurrent.error = "Senha atual incorreta"
                    }
                    newPass.length < 4 -> {
                        inputNew.error = "Mínimo de 4 caracteres (letras/números)"
                    }
                    newPass != confirmPass -> {
                        inputConfirm.error = "As senhas não coincidem"
                    }
                    else -> {
                        prefs.masterPin = newPass
                        Toast.makeText(this, "Senha Mestre alterada com sucesso!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showRandomPasswordResetDialog() {
        var generatedPassword = generateComplexPassword(16)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, dpToPx(12), pad, dpToPx(8))
        }

        val infoTv = TextView(this).apply {
            text = "Uma nova senha complexa e aleatória foi gerada. Copie-a e guarde em um local seguro antes de aplicar o reset:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
        }

        val passwordDisplay = TextView(this).apply {
            text = generatedPassword
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card)
            val pad = dpToPx(14)
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
                bottomMargin = dpToPx(12)
            }
            layoutParams = params
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnCopy = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(6)
            }
            text = "Copiar Senha"
            setOnClickListener {
                copyPasswordToClipboard(generatedPassword)
            }
        }

        val btnRegenerate = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "Gerar Outra"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_blue))
            setOnClickListener {
                generatedPassword = generateComplexPassword(16)
                passwordDisplay.text = generatedPassword
            }
        }

        actionsRow.addView(btnCopy)
        actionsRow.addView(btnRegenerate)

        container.addView(infoTv)
        container.addView(passwordDisplay)
        container.addView(actionsRow)

        AlertDialog.Builder(this)
            .setTitle("Reset de Senha (Aleatória)")
            .setView(container)
            .setPositiveButton("Aplicar Reset e Copiar") { _, _ ->
                prefs.masterPin = generatedPassword
                copyPasswordToClipboard(generatedPassword)
                Toast.makeText(
                    this,
                    "Senha Mestre resetada e copiada para a área de transferência!",
                    Toast.LENGTH_LONG
                ).show()
                updateUI()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun generateComplexPassword(length: Int = 16): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#$%&*?-_"
        val allChars = upper + lower + digits + symbols
        val random = SecureRandom()

        val passwordChars = mutableListOf<Char>()
        // Garantir complexidade (maiúsculas, minúsculas, números e símbolos)
        repeat(3) { passwordChars.add(upper[random.nextInt(upper.length)]) }
        repeat(3) { passwordChars.add(lower[random.nextInt(lower.length)]) }
        repeat(3) { passwordChars.add(digits[random.nextInt(digits.length)]) }
        repeat(3) { passwordChars.add(symbols[random.nextInt(symbols.length)]) }

        while (passwordChars.size < length) {
            passwordChars.add(allChars[random.nextInt(allChars.length)])
        }

        // Embaralhar usando SecureRandom
        for (i in passwordChars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = passwordChars[i]
            passwordChars[i] = passwordChars[j]
            passwordChars[j] = tmp
        }

        return passwordChars.joinToString("")
    }

    private fun copyPasswordToClipboard(password: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Senha Mestre FocusLock", password)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Senha copiada para a área de transferência!", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val isManual = prefs.isManualLockActive
        val isScheduleActive = prefs.isAnyScheduleActiveNow()
        val isLocked = isManual || isScheduleActive
        val blockedCount = prefs.getBlockedPackages().size

        if (isLocked) {
            binding.ivLockStatus.setImageResource(R.drawable.ic_lock)
            binding.ivLockStatus.setColorFilter(ContextCompat.getColor(this, R.color.accent_red))
            binding.tvLockStatus.text = if (isManual) {
                "STATUS: BLOQUEIO MANUAL ATIVO"
            } else {
                "STATUS: HORÁRIO DE FOCO ATIVO"
            }
            binding.tvLockStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.tvLockSubtitle.text = if (blockedCount == 0) {
                "Todos os apps bloqueados (exceto WhatsApp)"
            } else {
                "$blockedCount aplicativo(s) selecionado(s) bloqueado(s)"
            }
            binding.btnToggleLock.text = "DESBLOQUEAR (EXIGE SENHA MESTRE)"
            binding.btnToggleLock.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.accent_red)
        } else {
            binding.ivLockStatus.setImageResource(R.drawable.ic_focus_shield)
            binding.ivLockStatus.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvLockStatus.text = "STATUS: LIVRE"
            binding.tvLockStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.tvLockSubtitle.text = "Todos os aplicativos estão liberados no momento"
            binding.btnToggleLock.text = "ATIVAR BLOQUEIO AGORA"
            binding.btnToggleLock.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.accent_blue)
        }

        val nfcTags = prefs.getNfcTags()
        if (nfcTags.isNotEmpty()) {
            val tagsSummary = nfcTags.joinToString("\n") { "• ${it.name}: ${it.uid}" }
            binding.tvNfcInfo.text = "Tags NFC Vinculadas (${nfcTags.size}):\n$tagsSummary"
        } else {
            binding.tvNfcInfo.text = "Nenhuma Tag NFC vinculada. Toque no botão abaixo para cadastrar suas tags (Casa, Trabalho, etc.)."
        }

        binding.tvBlockedAppsSummary.text = if (blockedCount == 0) {
            "Apps bloqueados (Global): Todos os apps (exceto WhatsApp e Bancos)"
        } else {
            "Apps bloqueados (Global): $blockedCount aplicativo(s) específico(s)"
        }

        val todayBlocked = prefs.getBlockedAttemptsToday()
        binding.tvTodayBlockedBadge.text = "🛡️ $todayBlocked tentativa(s) de distração bloqueada(s) hoje"

        binding.tvStrictnessModeInfo.text = when (prefs.strictnessMode) {
            BlockPreferences.MODE_DEEP_FOCUS_NFC -> "Modo Atual: Deep Focus — Opal/Brick (Exclusivo Tag NFC)"
            BlockPreferences.MODE_MINDFUL_DELAY -> "Modo Atual: Pausa Consciente — ScreenZen (Espera de 15s)"
            else -> "Modo Atual: Normal (Senha Mestre ou NFC imediato)"
        }
        binding.switchAntiTamper.isChecked = prefs.isAntiTamperEnabled
        binding.switchCognitiveChallenge.isChecked = prefs.isCognitiveChallengeEnabled
        binding.tvCustomQuotePreview.text = "Frase de foco: \"${prefs.customLockQuote}\""

        binding.switchDailyBudget.isChecked = prefs.isDailyBudgetEnabled
        val usedOpens = prefs.getDailyOpensUsedToday()
        val maxOpens = prefs.dailyMaxOpens
        val sessionMins = prefs.dailySessionMinutes
        binding.tvDailyBudgetSummary.text =
            "Limite: $maxOpens aberturas/dia • $sessionMins min por sessão (Usado hoje: $usedOpens/$maxOpens)"

        renderSchedulesList()
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun checkPermissionsStatus() {
        val isUsageStatsEnabled = AppBlockerService.isServiceEnabled(this)
        val isOverlayEnabled = Settings.canDrawOverlays(this)
        val isBatteryIgnored = isBatteryOptimizationIgnored()

        if (isUsageStatsEnabled) {
            AppBlockerService.ensureRunning(this)
        }

        if (isUsageStatsEnabled && isOverlayEnabled && isBatteryIgnored) {
            binding.cardAccessibilityWarning.visibility = View.GONE
        } else {
            binding.cardAccessibilityWarning.visibility = View.VISIBLE
            binding.btnEnableAccessibility.visibility = if (isUsageStatsEnabled) View.GONE else View.VISIBLE
            binding.btnEnableOverlay.visibility = if (isOverlayEnabled) View.GONE else View.VISIBLE
            binding.btnDisableBatteryOpt.visibility = if (isBatteryIgnored) View.GONE else View.VISIBLE

            if (isUsageStatsEnabled && isOverlayEnabled && !isBatteryIgnored) {
                binding.tvPermissionWarningTitle.text = "⚡ Recomendado: Evitar Pausa em Segundo Plano"
                binding.tvPermissionWarningDesc.text =
                    "Toque abaixo para permitir que o FocusLock continue monitorando seus apps sem ser suspenso pela economia de bateria do Android."
            } else {
                binding.tvPermissionWarningTitle.text = "⚠️ Permissões Necessárias para o Bloqueio"
                binding.tvPermissionWarningDesc.text =
                    "Ative o Acesso ao Uso e a Sobreposição de Tela (modo seguro compatível com apps de banco, sem usar Acessibilidade)."
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
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
        var isResumed: Boolean = false
            private set
    }
}
