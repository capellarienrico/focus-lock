package com.focuslock.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NfcTagProfile(
    val uid: String,
    val name: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("name", name)
    }

    companion object {
        fun fromJson(json: JSONObject): NfcTagProfile {
            return NfcTagProfile(
                uid = json.optString("uid", ""),
                name = json.optString("name", "Tag NFC")
            )
        }
    }
}

class BlockPreferences(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)

    // Caches em memória (0 alocações / 0 JSON parsing durante o loop de monitoramento)
    @Volatile
    private var cachedBlockedPackages: Set<String>? = null
    @Volatile
    private var cachedFilteredNotifPackages: Set<String>? = null
    @Volatile
    private var cachedTimeLimitedPackages: Set<String>? = null
    @Volatile
    private var cachedSchedules: List<BlockSchedule>? = null
    @Volatile
    private var cachedNfcTags: List<NfcTagProfile>? = null
    @Volatile
    private var cachedScheduleEvalMinute: Long = -1L
    @Volatile
    private var cachedAnyScheduleActive: Boolean = false

    var isMasterPasswordConfigured: Boolean
        get() = prefs.getBoolean(KEY_PASSWORD_CONFIGURED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PASSWORD_CONFIGURED, value).apply()
        }

    var strictnessMode: String
        get() = prefs.getString(KEY_STRICTNESS_MODE, MODE_MINDFUL_DELAY) ?: MODE_MINDFUL_DELAY
        set(value) {
            prefs.edit().putString(KEY_STRICTNESS_MODE, value).apply()
        }

    var isAntiTamperEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_TAMPER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ANTI_TAMPER, value).apply()
        }

    var isBlockShortsReelsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORTS_REELS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_SHORTS_REELS, value).apply()
        }

    var isCognitiveChallengeEnabled: Boolean
        get() = prefs.getBoolean(KEY_COGNITIVE_CHALLENGE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_COGNITIVE_CHALLENGE, value).apply()
        }

    var customLockQuote: String
        get() = prefs.getString(KEY_CUSTOM_LOCK_QUOTE, DEFAULT_LOCK_QUOTE) ?: DEFAULT_LOCK_QUOTE
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_LOCK_QUOTE, value).apply()
        }

    // =========================================================================
    // Limite Diário de Tempo de Tela por App (Estilo Minimalist Phone / Wellbeing)
    // =========================================================================

    var isDailyTimeLimitEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_TIME_LIMIT_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DAILY_TIME_LIMIT_ENABLED, value).apply()
        }

    var dailyTimeLimitMinutes: Int
        get() = prefs.getInt(KEY_DAILY_TIME_LIMIT_MINUTES, 30)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_TIME_LIMIT_MINUTES, value).apply()
        }

    var timeLimitSnoozeUntilMillis: Long
        get() = prefs.getLong(KEY_TIME_LIMIT_SNOOZE_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_TIME_LIMIT_SNOOZE_UNTIL, value).apply()
        }

    fun snoozeTimeLimitForMinutes(minutes: Int) {
        timeLimitSnoozeUntilMillis = System.currentTimeMillis() + (minutes * 60_000L)
    }

    fun isTimeLimitSnoozed(): Boolean {
        return System.currentTimeMillis() < timeLimitSnoozeUntilMillis
    }

    fun getTimeLimitedPackages(): Set<String> {
        cachedTimeLimitedPackages?.let { return it }
        return readStringSetJson(KEY_TIME_LIMITED_PACKAGES_JSON).also {
            cachedTimeLimitedPackages = it
        }
    }

    fun saveTimeLimitedPackages(packages: Set<String>) {
        val copy = packages.toSet()
        cachedTimeLimitedPackages = copy
        writeStringSetJson(KEY_TIME_LIMITED_PACKAGES_JSON, copy)
    }

    // =========================================================================
    // Filtro de Notificações (Estilo Minimalist Phone)
    // =========================================================================

    var isNotificationFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_FILTER_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIF_FILTER_ENABLED, value).apply()
        }

    var notificationFilterMode: String
        get() = prefs.getString(KEY_NOTIF_FILTER_MODE, NOTIF_MODE_ALWAYS) ?: NOTIF_MODE_ALWAYS
        set(value) {
            prefs.edit().putString(KEY_NOTIF_FILTER_MODE, value).apply()
        }

    var floatingPillMode: String
        get() = prefs.getString(KEY_FLOATING_PILL_MODE, PILL_MODE_HOME_ONLY) ?: PILL_MODE_HOME_ONLY
        set(value) {
            prefs.edit().putString(KEY_FLOATING_PILL_MODE, value).apply()
        }

    var floatingPillYOffsetPx: Int
        get() = prefs.getInt(KEY_FLOATING_PILL_Y_OFFSET, 240)
        set(value) {
            prefs.edit().putInt(KEY_FLOATING_PILL_Y_OFFSET, value).apply()
        }

    var floatingPillAlignLeft: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_PILL_ALIGN_LEFT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FLOATING_PILL_ALIGN_LEFT, value).apply()
        }

    fun getFilteredNotificationPackages(): Set<String> {
        cachedFilteredNotifPackages?.let { return it }
        return readStringSetJson(KEY_FILTERED_NOTIF_PACKAGES_JSON).also {
            cachedFilteredNotifPackages = it
        }
    }

    fun saveFilteredNotificationPackages(packages: Set<String>) {
        val copy = packages.toSet()
        cachedFilteredNotifPackages = copy
        writeStringSetJson(KEY_FILTERED_NOTIF_PACKAGES_JSON, copy)
    }

    fun shouldFilterNotificationsNow(): Boolean {
        if (!isNotificationFilterEnabled) return false
        return if (notificationFilterMode == NOTIF_MODE_ONLY_DURING_FOCUS) {
            isBlockingCurrentlyActive()
        } else {
            true
        }
    }

    // =========================================================================
    // Orçamento Diário de Aberturas (Estilo ScreenZen)
    // =========================================================================

    var isDailyBudgetEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_BUDGET_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DAILY_BUDGET_ENABLED, value).apply()
        }

    var dailyMaxOpens: Int
        get() = prefs.getInt(KEY_DAILY_MAX_OPENS, 5)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_MAX_OPENS, value).apply()
        }

    var dailySessionMinutes: Int
        get() = prefs.getInt(KEY_DAILY_SESSION_MINUTES, 5)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_SESSION_MINUTES, value).apply()
        }

    fun getDailyOpensUsedToday(): Int {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_DAILY_OPENS_DATE, "") ?: ""
        if (savedDate != today) return 0
        return prefs.getInt(KEY_DAILY_OPENS_USED, 0)
    }

    fun incrementDailyOpensUsed() {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_DAILY_OPENS_DATE, "") ?: ""
        val current = if (savedDate == today) prefs.getInt(KEY_DAILY_OPENS_USED, 0) else 0
        prefs.edit()
            .putString(KEY_DAILY_OPENS_DATE, today)
            .putInt(KEY_DAILY_OPENS_USED, current + 1)
            .apply()
    }

    fun isDailyBudgetExhausted(): Boolean {
        if (!isDailyBudgetEnabled) return false
        return getDailyOpensUsedToday() >= dailyMaxOpens
    }

    fun getBlockedAttemptsToday(): Int {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_STATS_DATE, "") ?: ""
        if (savedDate != today) {
            return 0
        }
        return prefs.getInt(KEY_BLOCKED_ATTEMPTS_TODAY, 0)
    }

    fun recordBlockedAttempt() {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_STATS_DATE, "") ?: ""
        val currentCount = if (savedDate == today) {
            prefs.getInt(KEY_BLOCKED_ATTEMPTS_TODAY, 0)
        } else {
            0
        }
        prefs.edit()
            .putString(KEY_STATS_DATE, today)
            .putInt(KEY_BLOCKED_ATTEMPTS_TODAY, currentCount + 1)
            .apply()
    }

    private fun currentDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    var isManualLockActive: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_LOCK, false)
        set(value) {
            val editor = prefs.edit().putBoolean(KEY_MANUAL_LOCK, value)
            if (value) {
                cachedScheduleEvalMinute = -1L
                editor.putLong(KEY_SCHEDULE_SNOOZE_UNTIL, 0L)
                editor.putLong(KEY_TIME_LIMIT_SNOOZE_UNTIL, 0L)
            }
            editor.apply()
        }

    var scheduleSnoozeUntilMillis: Long
        get() = prefs.getLong(KEY_SCHEDULE_SNOOZE_UNTIL, 0L)
        set(value) {
            cachedScheduleEvalMinute = -1L
            prefs.edit().putLong(KEY_SCHEDULE_SNOOZE_UNTIL, value).apply()
        }

    fun snoozeSchedulesForMinutes(minutes: Int) {
        scheduleSnoozeUntilMillis = System.currentTimeMillis() + (minutes * 60_000L)
    }

    fun isScheduleSnoozed(): Boolean {
        return System.currentTimeMillis() < scheduleSnoozeUntilMillis
    }

    var registeredNfcTagId: String?
        get() = getNfcTags().firstOrNull()?.uid
        set(value) {
            if (value == null) {
                saveNfcTags(emptyList())
            } else {
                saveNfcTags(listOf(NfcTagProfile(uid = value, name = "Tag Principal")))
            }
        }

    fun getNfcTags(): MutableList<NfcTagProfile> {
        cachedNfcTags?.let { return it.toMutableList() }
        val jsonStr = prefs.getString(KEY_NFC_TAGS_JSON, null)
        val loaded: List<NfcTagProfile> = if (jsonStr != null) {
            try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<NfcTagProfile>()
                for (i in 0 until arr.length()) {
                    val item = NfcTagProfile.fromJson(arr.getJSONObject(i))
                    if (item.uid.isNotBlank()) list.add(item)
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            val legacy = prefs.getString(KEY_NFC_TAG, null)
            if (!legacy.isNullOrBlank()) {
                listOf(NfcTagProfile(uid = legacy, name = "Tag de Casa"))
            } else {
                emptyList()
            }
        }
        cachedNfcTags = loaded
        return loaded.toMutableList()
    }

    fun saveNfcTags(tags: List<NfcTagProfile>) {
        val copy = tags.toList()
        cachedNfcTags = copy
        val arr = JSONArray()
        copy.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_NFC_TAGS_JSON, arr.toString())
            .remove(KEY_NFC_TAG)
            .apply()
    }

    fun isNfcTagAllowed(uid: String): Boolean {
        val tags = cachedNfcTags ?: getNfcTags()
        return tags.isEmpty() || tags.any { it.uid.equals(uid, ignoreCase = true) }
    }

    fun findNfcTagName(uid: String): String? {
        val tags = cachedNfcTags ?: getNfcTags()
        return tags.firstOrNull { it.uid.equals(uid, ignoreCase = true) }?.name
    }

    var masterPin: String
        get() = prefs.getString(KEY_MASTER_PIN, "1234") ?: "1234"
        set(value) {
            prefs.edit()
                .putString(KEY_MASTER_PIN, value)
                .putBoolean(KEY_PASSWORD_CONFIGURED, true)
                .apply()
        }

    fun getSchedules(): MutableList<BlockSchedule> {
        cachedSchedules?.let { return it.toMutableList() }
        val loaded = loadSchedulesFromDisk()
        cachedSchedules = loaded
        return loaded.toMutableList()
    }

    private fun getSchedulesCachedReadOnly(): List<BlockSchedule> {
        cachedSchedules?.let { return it }
        return loadSchedulesFromDisk().also { cachedSchedules = it }
    }

    private fun loadSchedulesFromDisk(): List<BlockSchedule> {
        val jsonStr = prefs.getString(KEY_SCHEDULES, null) ?: return defaultSchedules()
        return try {
            val arr = JSONArray(jsonStr)
            val list = ArrayList<BlockSchedule>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(BlockSchedule.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (_: Exception) {
            defaultSchedules()
        }
    }

    fun saveSchedules(schedules: List<BlockSchedule>) {
        val copy = schedules.toList()
        cachedSchedules = copy
        cachedScheduleEvalMinute = -1L
        val arr = JSONArray()
        copy.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SCHEDULES, arr.toString()).apply()
    }

    fun hasAnyEnabledSchedule(): Boolean {
        return getSchedulesCachedReadOnly().any { it.isEnabled }
    }

    private fun defaultSchedules(): MutableList<BlockSchedule> {
        return mutableListOf(
            BlockSchedule(
                id = "default_work",
                name = "Horário Comercial",
                startHour = 9,
                startMinute = 0,
                endHour = 18,
                endMinute = 0,
                daysOfWeek = listOf(1, 2, 3, 4, 5, 6, 7),
                isEnabled = false
            )
        )
    }

    fun getBlockedPackages(): Set<String> {
        cachedBlockedPackages?.let { return it }
        val jsonStr = prefs.getString(KEY_BLOCKED_PACKAGES_JSON, null)
        val loaded = if (jsonStr != null) {
            readStringSetJson(KEY_BLOCKED_PACKAGES_JSON)
        } else {
            prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        }
        cachedBlockedPackages = loaded
        return loaded
    }

    fun saveBlockedPackages(packages: Set<String>) {
        val copy = packages.toSet()
        cachedBlockedPackages = copy
        val arr = JSONArray()
        copy.forEach { arr.put(it) }
        prefs.edit()
            .putString(KEY_BLOCKED_PACKAGES_JSON, arr.toString())
            .remove(KEY_BLOCKED_PACKAGES)
            .apply()
    }

    private fun readStringSetJson(key: String): Set<String> {
        val jsonStr = prefs.getString(key, null) ?: return emptySet()
        return try {
            val arr = JSONArray(jsonStr)
            val set = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i)
                if (pkg.isNotBlank()) set.add(pkg)
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun writeStringSetJson(key: String, values: Set<String>) {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun isAnyScheduleActiveNow(): Boolean {
        if (isScheduleSnoozed()) return false
        val schedules = getSchedulesCachedReadOnly()
        if (schedules.none { it.isEnabled }) return false

        val currentEpochMinute = System.currentTimeMillis() / 60_000L
        if (currentEpochMinute == cachedScheduleEvalMinute) {
            return cachedAnyScheduleActive
        }
        val active = schedules.any { it.isActiveNow() }
        cachedAnyScheduleActive = active
        cachedScheduleEvalMinute = currentEpochMinute
        return active
    }

    // =========================================================================
    // Modo EXTREME (trava total; so Camera/Telefone; sai com Senha Forte ou NFC EXTREME)
    // =========================================================================

    var isExtremeActive: Boolean
        get() = prefs.getBoolean(KEY_EXTREME_ACTIVE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EXTREME_ACTIVE, value).apply()
        }

    fun hasExtremePassword(): Boolean = prefs.getString(KEY_EXTREME_HASH, null) != null

    /** Regras da senha forte: >= 10 caracteres, >= 3 tipos (minuscula, MAIUSCULA, digito, simbolo), != Senha Mestre. */
    fun validateExtremePassword(pw: String): String? {
        if (pw.length < 10) return "Use pelo menos 10 caracteres."
        val kinds = listOf(
            pw.any { it.isLowerCase() },
            pw.any { it.isUpperCase() },
            pw.any { it.isDigit() },
            pw.any { !it.isLetterOrDigit() }
        ).count { it }
        if (kinds < 3) return "Misture ao menos 3 tipos: minusculas, MAIUSCULAS, numeros e simbolos."
        if (pw == masterPin) return "A senha EXTREME deve ser diferente da Senha Mestre."
        return null
    }

    fun setExtremePassword(pw: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_EXTREME_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            .putString(KEY_EXTREME_HASH, android.util.Base64.encodeToString(pbkdf2(pw, salt), android.util.Base64.NO_WRAP))
            .apply()
        resetExtremeFailures()
    }

    private fun pbkdf2(pw: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Verifica a senha com limitacao de tentativas (5 erros => espera crescente). */
    fun verifyExtremePassword(pw: String): Boolean {
        if (extremeLockoutRemainingMs() > 0) return false
        val saltB64 = prefs.getString(KEY_EXTREME_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_EXTREME_HASH, null) ?: return false
        val salt = android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP)
        val expected = android.util.Base64.decode(hashB64, android.util.Base64.NO_WRAP)
        val ok = java.security.MessageDigest.isEqual(expected, pbkdf2(pw, salt))
        if (ok) {
            resetExtremeFailures()
        } else {
            val fails = prefs.getInt(KEY_EXTREME_FAILS, 0) + 1
            val editor = prefs.edit().putInt(KEY_EXTREME_FAILS, fails)
            if (fails >= 5) {
                val waitSec = minOf(30L shl (fails - 5).coerceAtMost(5), 600L)
                editor.putLong(KEY_EXTREME_LOCKOUT_UNTIL, System.currentTimeMillis() + waitSec * 1000L)
            }
            editor.apply()
        }
        return ok
    }

    fun extremeLockoutRemainingMs(): Long {
        return (prefs.getLong(KEY_EXTREME_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun resetExtremeFailures() {
        prefs.edit().putInt(KEY_EXTREME_FAILS, 0).putLong(KEY_EXTREME_LOCKOUT_UNTIL, 0L).apply()
    }

    fun getExtremeNfcTags(): MutableList<NfcTagProfile> {
        val raw = prefs.getString(KEY_EXTREME_NFC_JSON, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<NfcTagProfile>()
            for (i in 0 until arr.length()) {
                val t = NfcTagProfile.fromJson(arr.getJSONObject(i))
                if (t.uid.isNotBlank()) list.add(t)
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveExtremeNfcTags(tags: List<NfcTagProfile>) {
        val arr = JSONArray()
        tags.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_EXTREME_NFC_JSON, arr.toString()).apply()
    }

    /** Diferente das tags normais: so vale tag cadastrada explicitamente (lista vazia nao libera nada). */
    fun isExtremeNfcTag(uid: String): Boolean {
        return getExtremeNfcTags().any { it.uid.equals(uid, ignoreCase = true) }
    }

    @Volatile
    private var cachedExtremeAllowed: Set<String> = emptySet()
    @Volatile
    private var cachedExtremeAllowedAt: Long = 0L

    /** Pacotes liberados no EXTREME: Camera, Telefone/chamadas, Home (Tela Inicial), Teclado, NFC e FocusLock. */
    fun isExtremeAllowedPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val lower = packageName.lowercase()

        // 1. O próprio aplicativo FocusLock
        if (packageName == appContext.packageName || lower.startsWith("com.focuslock")) return true

        // 2. Tela Inicial (Launcher nativo) - celular livre para tocar na câmera/telefone/focuslock
        if (isHomeLauncherPackage(packageName)) return true

        // 3. Interface do sistema Android, NFC e controles de permissão
        if (packageName == "com.android.systemui" ||
            packageName == "android" ||
            packageName == "com.android.nfc" ||
            lower.contains("nfc") ||
            lower.contains("permissioncontroller")
        ) return true

        // 4. Teclados e IMEs do sistema (necessário para digitar a senha no bloqueador)
        if (getDynamicSystemAllowedPackages().contains(packageName) ||
            lower.contains("keyboard") ||
            lower.contains("gboard") ||
            lower.contains("inputmethod") ||
            lower.contains("latinime") ||
            lower.contains("swiftkey") ||
            lower.contains("honeyboard")
        ) return true

        // 5. Telefone, Discador, Telecom, Incall e chamadas de emergência
        if (packageName == "com.android.phone" ||
            packageName == "com.android.server.telecom" ||
            packageName == "com.google.android.dialer" ||
            lower.contains("dialer") ||
            lower.contains("telecom") ||
            lower.contains("incall") ||
            lower.contains("emergency")
        ) return true

        // 6. Câmera e Discador padrão via resolução estrita MATCH_DEFAULT_ONLY (sem queryIntentActivities aberto)
        val now = System.currentTimeMillis()
        if (now - cachedExtremeAllowedAt > 60_000L || cachedExtremeAllowed.isEmpty()) {
            val result = mutableSetOf<String>()
            val pm = appContext.packageManager

            val cameraProbes = listOf(
                Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),
                Intent("android.media.action.STILL_IMAGE_CAMERA_SECURE")
            )
            for (probe in cameraProbes) {
                try {
                    val resolved = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
                    resolved?.activityInfo?.packageName?.let { result.add(it) }
                } catch (_: Exception) {}
            }

            try {
                val dialerIntent = Intent(Intent.ACTION_DIAL)
                val resolvedDialer = pm.resolveActivity(dialerIntent, PackageManager.MATCH_DEFAULT_ONLY)
                resolvedDialer?.activityInfo?.packageName?.let { result.add(it) }
            } catch (_: Exception) {}

            try {
                val telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                telecomManager?.defaultDialerPackage?.let { result.add(it) }
            } catch (_: Exception) {}

            cachedExtremeAllowed = result
            cachedExtremeAllowedAt = now
        }

        if (cachedExtremeAllowed.contains(packageName)) return true

        // 7. Câmeras nativas conhecidas de fabricantes
        return packageName == "com.google.android.GoogleCamera" ||
                packageName == "com.sec.android.app.camera" ||
                packageName == "com.android.camera" ||
                packageName == "com.android.camera2" ||
                packageName == "com.motorola.camera2" ||
                packageName == "com.motorola.camera3" ||
                packageName == "com.oppo.camera" ||
                packageName == "com.oneplus.camera" ||
                lower.endsWith(".camera")
    }

    fun isBlockingCurrentlyActive(): Boolean {
        return isExtremeActive || isManualLockActive || isAnyScheduleActiveNow()
    }

    /**
     * Determina se o pacote deve ser bloqueado no momento atual (bloqueio manual ou agendamento).
     */
    fun shouldBlockNow(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        // 0. Modo EXTREME ativo: Perfil pré-definido onde TUDO é bloqueado,
        // exceto Câmera, Telefone/emergência, Tela Inicial, Teclado e FocusLock.
        if (isExtremeActive) {
            return !isExtremeAllowedPackage(packageName)
        }

        // 1. Apps que NUNCA podem ser bloqueados (WhatsApp, Bancos, Telefone, Teclado, Launcher, FocusLock)
        if (isAlwaysAllowed(packageName)) {
            return false
        }

        val globalBlockedList = getBlockedPackages()

        // 2. Bloqueio Manual ativo
        if (isManualLockActive) {
            if (globalBlockedList.isEmpty() || globalBlockedList.contains(packageName)) {
                return true
            }
        }

        // 3. Verificação de Agendamentos / Blocos de Horário ativos (com suporte a lista por horário)
        if (isAnyScheduleActiveNow()) {
            val schedules = getSchedulesCachedReadOnly()
            for (schedule in schedules) {
                if (!schedule.isActiveNow()) continue
                val scheduleApps = schedule.customBlockedPackages
                val isBlockedByThisSchedule = if (scheduleApps.isNotEmpty()) {
                    scheduleApps.contains(packageName)
                } else {
                    globalBlockedList.isEmpty() || globalBlockedList.contains(packageName)
                }
                if (isBlockedByThisSchedule) {
                    return true
                }
            }
        }

        return false
    }

    @Volatile
    private var cachedAllowedSysPackages: Set<String> = emptySet()
    @Volatile
    private var cachedHomePackages: Set<String> = emptySet()
    @Volatile
    private var lastCacheTimeMillis: Long = 0L

    private fun refreshDynamicSystemPackages() {
        val now = System.currentTimeMillis()
        if (now - lastCacheTimeMillis < 60_000L && cachedAllowedSysPackages.isNotEmpty()) {
            return
        }
        val sysResult = mutableSetOf<String>()
        val homeResult = mutableSetOf<String>()
        try {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { sysResult.add(it.packageName) }
            imm?.inputMethodList?.forEach { sysResult.add(it.packageName) }
        } catch (_: Exception) {
        }
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            appContext.packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName }
                .forEach {
                    sysResult.add(it)
                    homeResult.add(it)
                }
            appContext.packageManager.queryIntentActivities(homeIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .forEach {
                    sysResult.add(it)
                    homeResult.add(it)
                }
            val resolveInfo = appContext.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName?.let {
                sysResult.add(it)
                homeResult.add(it)
            }
        } catch (_: Exception) {
        }
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL)
            appContext.packageManager.queryIntentActivities(dialIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .forEach { sysResult.add(it) }
        } catch (_: Exception) {
        }
        cachedAllowedSysPackages = sysResult
        cachedHomePackages = homeResult
        lastCacheTimeMillis = now
    }

    private fun getDynamicSystemAllowedPackages(): Set<String> {
        refreshDynamicSystemPackages()
        return cachedAllowedSysPackages
    }

    fun isHomeLauncherPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        refreshDynamicSystemPackages()
        if (cachedHomePackages.contains(packageName)) return true
        val lower = packageName.lowercase()
        return packageName == "com.miui.home" ||
                lower.endsWith(".home") ||
                lower.contains("launcher") ||
                lower.contains("trebuchet")
    }

    /**
     * Verifica se o pacote é de banco, sistema crítico, teclado, discador ou launcher
     * (não inclui o WhatsApp, permitindo opcionalmente filtrar notificações do WhatsApp sem bloqueá-lo).
     */
    fun isBankingOrCriticalSystemPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()

        // Aplicativos de Banco e Pagamentos (sempre liberados e nunca monitorados)
        if (BANKING_PACKAGES.contains(packageName) ||
            lower.contains("nubank") ||
            lower.startsWith("com.itau") ||
            lower.startsWith("com.bradesco") ||
            lower.startsWith("br.com.bb.") ||
            lower.startsWith("br.com.intermedium") ||
            lower.startsWith("com.c6bank") ||
            lower.startsWith("com.santander") ||
            lower.startsWith("br.gov.caixa")
        ) return true

        // O próprio aplicativo FocusLock
        if (packageName == appContext.packageName || lower.startsWith("com.focuslock")) return true

        // Discadores e telefone de emergência
        if (packageName == "com.android.phone" ||
            lower.contains("dialer") ||
            lower.contains("telecom") ||
            lower.contains("incall") ||
            lower.contains("emergency")
        ) return true

        // UI do sistema, configurações do Android e instalador/permissões
        if (packageName == "com.android.systemui" ||
            packageName == "android" ||
            packageName == "com.android.settings" ||
            lower.contains("permissioncontroller") ||
            lower.contains("packageinstaller") ||
            lower.contains("securitycenter")
        ) return true

        // Teclados / IMEs, Launchers do sistema e Gavetas/Buscas de Aplicativos (Samsung Finder, Xiaomi Drawer, etc.)
        if (packageName == "com.miui.home" ||
            packageName == "com.samsung.android.app.galaxyfinder" ||
            packageName == "com.samsung.android.app.cocktailbarservice" ||
            packageName == "com.mi.android.globalminusscreen" ||
            packageName == "com.miui.personalassistant" ||
            packageName == "com.android.quicksearchbox" ||
            packageName == "com.heytap.quicksearchbox" ||
            packageName == "com.oppo.quicksearchbox" ||
            packageName == "com.coloros.assistantscreen" ||
            packageName == "com.google.android.as" ||
            packageName == "com.android.wallpaper" ||
            packageName == "ginlemon.flowerfree" ||
            packageName == "com.bllocosn" ||
            packageName == "ru.execbit.aiolauncher" ||
            packageName == "com.ss.squarehome2" ||
            lower.endsWith(".home") ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("gboard") ||
            lower.contains("honeyboard") ||
            lower.contains("swiftkey") ||
            lower.contains("latinime") ||
            lower.contains("launcher") ||
            lower.contains("trebuchet")
        ) return true

        if (getDynamicSystemAllowedPackages().contains(packageName)) {
            return true
        }

        return false
    }

    fun isAlwaysAllowed(packageName: String): Boolean {
        // WhatsApp (padrão e business) nunca pode ser bloqueado de abrir
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") return true
        return isBankingOrCriticalSystemPackage(packageName)
    }

    // =========================================================================
    // Backup & Restauração em JSON
    // =========================================================================

    fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("version", 3)
        root.put("strictnessMode", strictnessMode)
        root.put("antiTamper", isAntiTamperEnabled)
        root.put("cognitiveChallenge", isCognitiveChallengeEnabled)
        root.put("customLockQuote", customLockQuote)
        root.put("dailyBudgetEnabled", isDailyBudgetEnabled)
        root.put("dailyMaxOpens", dailyMaxOpens)
        root.put("dailySessionMinutes", dailySessionMinutes)
        root.put("dailyTimeLimitEnabled", isDailyTimeLimitEnabled)
        root.put("dailyTimeLimitMinutes", dailyTimeLimitMinutes)
        root.put("notifFilterEnabled", isNotificationFilterEnabled)
        root.put("notifFilterMode", notificationFilterMode)
        root.put("floatingPillMode", floatingPillMode)
        root.put("blockedPackages", JSONArray(getBlockedPackages().toList()))
        root.put("filteredNotifPackages", JSONArray(getFilteredNotificationPackages().toList()))
        root.put("timeLimitedPackages", JSONArray(getTimeLimitedPackages().toList()))

        val schedulesArr = JSONArray()
        getSchedules().forEach { schedulesArr.put(it.toJson()) }
        root.put("schedules", schedulesArr)

        val tagsArr = JSONArray()
        getNfcTags().forEach { tagsArr.put(it.toJson()) }
        root.put("nfcTags", tagsArr)

        val extremeTagsArr = JSONArray()
        getExtremeNfcTags().forEach { extremeTagsArr.put(it.toJson()) }
        root.put("extremeNfcTags", extremeTagsArr)

        return root.toString(2)
    }

    fun importBackupJson(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            strictnessMode = root.optString("strictnessMode", strictnessMode)
            isAntiTamperEnabled = root.optBoolean("antiTamper", isAntiTamperEnabled)
            isCognitiveChallengeEnabled = root.optBoolean("cognitiveChallenge", isCognitiveChallengeEnabled)
            customLockQuote = root.optString("customLockQuote", customLockQuote)
            isDailyBudgetEnabled = root.optBoolean("dailyBudgetEnabled", isDailyBudgetEnabled)
            dailyMaxOpens = root.optInt("dailyMaxOpens", dailyMaxOpens)
            dailySessionMinutes = root.optInt("dailySessionMinutes", dailySessionMinutes)
            isDailyTimeLimitEnabled = root.optBoolean("dailyTimeLimitEnabled", isDailyTimeLimitEnabled)
            dailyTimeLimitMinutes = root.optInt("dailyTimeLimitMinutes", dailyTimeLimitMinutes)
            isNotificationFilterEnabled = root.optBoolean("notifFilterEnabled", isNotificationFilterEnabled)
            notificationFilterMode = root.optString("notifFilterMode", notificationFilterMode)
            floatingPillMode = root.optString("floatingPillMode", floatingPillMode)

            root.optJSONArray("blockedPackages")?.let { arr ->
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i)
                    if (p.isNotBlank()) set.add(p)
                }
                saveBlockedPackages(set)
            }

            root.optJSONArray("filteredNotifPackages")?.let { arr ->
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i)
                    if (p.isNotBlank()) set.add(p)
                }
                saveFilteredNotificationPackages(set)
            }

            root.optJSONArray("timeLimitedPackages")?.let { arr ->
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i)
                    if (p.isNotBlank()) set.add(p)
                }
                saveTimeLimitedPackages(set)
            }

            root.optJSONArray("schedules")?.let { arr ->
                val list = mutableListOf<BlockSchedule>()
                for (i in 0 until arr.length()) {
                    list.add(BlockSchedule.fromJson(arr.getJSONObject(i)))
                }
                saveSchedules(list)
            }

            root.optJSONArray("nfcTags")?.let { arr ->
                val list = mutableListOf<NfcTagProfile>()
                for (i in 0 until arr.length()) {
                    val item = NfcTagProfile.fromJson(arr.getJSONObject(i))
                    if (item.uid.isNotBlank()) list.add(item)
                }
                saveNfcTags(list)
            }

            root.optJSONArray("extremeNfcTags")?.let { arr ->
                val list = mutableListOf<NfcTagProfile>()
                for (i in 0 until arr.length()) {
                    val item = NfcTagProfile.fromJson(arr.getJSONObject(i))
                    if (item.uid.isNotBlank()) list.add(item)
                }
                saveExtremeNfcTags(list)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val MODE_NORMAL = "NORMAL"
        const val MODE_MINDFUL_DELAY = "MINDFUL_DELAY"
        const val MODE_DEEP_FOCUS_NFC = "DEEP_FOCUS_NFC"

        const val NOTIF_MODE_ALWAYS = "ALWAYS"
        const val NOTIF_MODE_ONLY_DURING_FOCUS = "ONLY_FOCUS"

        const val PILL_MODE_HOME_ONLY = "HOME_ONLY"
        const val PILL_MODE_ALWAYS = "ALWAYS"
        const val PILL_MODE_OFF = "OFF"

        const val DEFAULT_LOCK_QUOTE = "Mantenha o foco no que realmente importa hoje."

        private val BANKING_PACKAGES = setOf(
            "com.nu.production",
            "com.itau",
            "com.itau.pers",
            "com.bradesco",
            "br.com.bb.android",
            "br.com.intermedium",
            "com.c6bank.app",
            "com.santander.app",
            "br.gov.caixa.tem",
            "br.com.gabba.Caixa",
            "com.mercadopago.wallet",
            "com.picpay",
            "br.com.xp.carteira",
            "com.btgpactual.pactualdigital",
            "br.com.neon",
            "br.com.next",
            "br.com.original.bank"
        )

        private const val KEY_PASSWORD_CONFIGURED = "password_configured"
        private const val KEY_EXTREME_ACTIVE = "extreme_active"
        private const val KEY_EXTREME_HASH = "extreme_pw_hash"
        private const val KEY_EXTREME_SALT = "extreme_pw_salt"
        private const val KEY_EXTREME_FAILS = "extreme_pw_fails"
        private const val KEY_EXTREME_LOCKOUT_UNTIL = "extreme_pw_lockout_until"
        private const val KEY_EXTREME_NFC_JSON = "extreme_nfc_tags_json"
        private const val KEY_STRICTNESS_MODE = "strictness_mode"
        private const val KEY_ANTI_TAMPER = "anti_tamper_v31_enabled"
        private const val KEY_BLOCK_SHORTS_REELS = "block_shorts_reels"
        private const val KEY_COGNITIVE_CHALLENGE = "cognitive_challenge"
        private const val KEY_CUSTOM_LOCK_QUOTE = "custom_lock_quote"
        private const val KEY_DAILY_TIME_LIMIT_ENABLED = "daily_time_limit_enabled"
        private const val KEY_DAILY_TIME_LIMIT_MINUTES = "daily_time_limit_minutes"
        private const val KEY_TIME_LIMIT_SNOOZE_UNTIL = "time_limit_snooze_until"
        private const val KEY_TIME_LIMITED_PACKAGES_JSON = "time_limited_packages_json"
        private const val KEY_NOTIF_FILTER_ENABLED = "notif_filter_enabled"
        private const val KEY_NOTIF_FILTER_MODE = "notif_filter_mode"
        private const val KEY_FLOATING_PILL_MODE = "floating_pill_mode"
        private const val KEY_FLOATING_PILL_Y_OFFSET = "floating_pill_y_offset"
        private const val KEY_FLOATING_PILL_ALIGN_LEFT = "floating_pill_align_left"
        private const val KEY_FILTERED_NOTIF_PACKAGES_JSON = "filtered_notif_packages_json"
        private const val KEY_DAILY_BUDGET_ENABLED = "daily_budget_enabled"
        private const val KEY_DAILY_MAX_OPENS = "daily_max_opens"
        private const val KEY_DAILY_SESSION_MINUTES = "daily_session_minutes"
        private const val KEY_DAILY_OPENS_DATE = "daily_opens_date"
        private const val KEY_DAILY_OPENS_USED = "daily_opens_used"
        private const val KEY_STATS_DATE = "stats_date"
        private const val KEY_BLOCKED_ATTEMPTS_TODAY = "blocked_attempts_today"
        private const val KEY_MANUAL_LOCK = "manual_lock"
        private const val KEY_SCHEDULE_SNOOZE_UNTIL = "schedule_snooze_until"
        private const val KEY_NFC_TAG = "nfc_tag_id"
        private const val KEY_NFC_TAGS_JSON = "nfc_tags_json"
        private const val KEY_MASTER_PIN = "master_pin"
        private const val KEY_SCHEDULES = "schedules_json"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        private const val KEY_BLOCKED_PACKAGES_JSON = "blocked_packages_json"
    }
}
