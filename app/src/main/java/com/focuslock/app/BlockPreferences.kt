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

    var isMasterPasswordConfigured: Boolean
        get() = prefs.getBoolean(KEY_PASSWORD_CONFIGURED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PASSWORD_CONFIGURED, value).commit()
        }

    var strictnessMode: String
        get() = prefs.getString(KEY_STRICTNESS_MODE, MODE_MINDFUL_DELAY) ?: MODE_MINDFUL_DELAY
        set(value) {
            prefs.edit().putString(KEY_STRICTNESS_MODE, value).commit()
        }

    var isAntiTamperEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_TAMPER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ANTI_TAMPER, value).commit()
        }

    var isBlockShortsReelsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORTS_REELS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_SHORTS_REELS, value).commit()
        }

    var isCognitiveChallengeEnabled: Boolean
        get() = prefs.getBoolean(KEY_COGNITIVE_CHALLENGE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_COGNITIVE_CHALLENGE, value).commit()
        }

    var customLockQuote: String
        get() = prefs.getString(KEY_CUSTOM_LOCK_QUOTE, DEFAULT_LOCK_QUOTE) ?: DEFAULT_LOCK_QUOTE
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_LOCK_QUOTE, value).commit()
        }

    var isDailyBudgetEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_BUDGET_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DAILY_BUDGET_ENABLED, value).commit()
        }

    var dailyMaxOpens: Int
        get() = prefs.getInt(KEY_DAILY_MAX_OPENS, 5)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_MAX_OPENS, value).commit()
        }

    var dailySessionMinutes: Int
        get() = prefs.getInt(KEY_DAILY_SESSION_MINUTES, 5)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_SESSION_MINUTES, value).commit()
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
            .commit()
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
            .commit()
    }

    private fun currentDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    var isManualLockActive: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_LOCK, false)
        set(value) {
            val editor = prefs.edit().putBoolean(KEY_MANUAL_LOCK, value)
            if (value) {
                editor.putLong(KEY_SCHEDULE_SNOOZE_UNTIL, 0L)
            }
            editor.commit()
        }

    var scheduleSnoozeUntilMillis: Long
        get() = prefs.getLong(KEY_SCHEDULE_SNOOZE_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SCHEDULE_SNOOZE_UNTIL, value).commit()
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
        val jsonStr = prefs.getString(KEY_NFC_TAGS_JSON, null)
        if (jsonStr != null) {
            return try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<NfcTagProfile>()
                for (i in 0 until arr.length()) {
                    val item = NfcTagProfile.fromJson(arr.getJSONObject(i))
                    if (item.uid.isNotBlank()) list.add(item)
                }
                list
            } catch (_: Exception) {
                mutableListOf()
            }
        }
        val legacy = prefs.getString(KEY_NFC_TAG, null)
        return if (!legacy.isNullOrBlank()) {
            mutableListOf(NfcTagProfile(uid = legacy, name = "Tag de Casa"))
        } else {
            mutableListOf()
        }
    }

    fun saveNfcTags(tags: List<NfcTagProfile>) {
        val arr = JSONArray()
        tags.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_NFC_TAGS_JSON, arr.toString())
            .remove(KEY_NFC_TAG)
            .commit()
    }

    fun isNfcTagAllowed(uid: String): Boolean {
        val tags = getNfcTags()
        return tags.isEmpty() || tags.any { it.uid.equals(uid, ignoreCase = true) }
    }

    fun findNfcTagName(uid: String): String? {
        return getNfcTags().firstOrNull { it.uid.equals(uid, ignoreCase = true) }?.name
    }

    var masterPin: String
        get() = prefs.getString(KEY_MASTER_PIN, "1234") ?: "1234"
        set(value) {
            prefs.edit()
                .putString(KEY_MASTER_PIN, value)
                .putBoolean(KEY_PASSWORD_CONFIGURED, true)
                .commit()
        }

    fun getSchedules(): MutableList<BlockSchedule> {
        val jsonStr = prefs.getString(KEY_SCHEDULES, null) ?: return defaultSchedules()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<BlockSchedule>()
            for (i in 0 until arr.length()) {
                list.add(BlockSchedule.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            defaultSchedules()
        }
    }

    fun saveSchedules(schedules: List<BlockSchedule>) {
        val arr = JSONArray()
        schedules.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SCHEDULES, arr.toString()).commit()
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
                daysOfWeek = listOf(1, 2, 3, 4, 5, 6, 7), // Todos os dias por padrão
                isEnabled = false
            )
        )
    }

    fun getBlockedPackages(): Set<String> {
        val jsonStr = prefs.getString(KEY_BLOCKED_PACKAGES_JSON, null)
        if (jsonStr != null) {
            return try {
                val arr = JSONArray(jsonStr)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val pkg = arr.optString(i)
                    if (pkg.isNotBlank()) set.add(pkg)
                }
                set
            } catch (e: Exception) {
                emptySet()
            }
        }
        return prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun saveBlockedPackages(packages: Set<String>) {
        val arr = JSONArray()
        packages.forEach { arr.put(it) }
        prefs.edit()
            .putString(KEY_BLOCKED_PACKAGES_JSON, arr.toString())
            .remove(KEY_BLOCKED_PACKAGES)
            .commit()
    }

    fun isAnyScheduleActiveNow(): Boolean {
        if (isScheduleSnoozed()) return false
        return getSchedules().any { it.isActiveNow() }
    }

    fun isBlockingCurrentlyActive(): Boolean {
        return isManualLockActive || isAnyScheduleActiveNow()
    }

    /**
     * Determina se o pacote deve ser bloqueado no momento atual.
     */
    fun shouldBlockNow(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        // 1. Apps que NUNCA podem ser bloqueados (WhatsApp, Telefone, Teclado, Launcher, FocusLock)
        if (isAlwaysAllowed(packageName)) {
            return false
        }

        val globalBlockedList = getBlockedPackages()

        // 2. Bloqueio Manual ativo
        if (isManualLockActive) {
            return globalBlockedList.isEmpty() || globalBlockedList.contains(packageName)
        }

        // 3. Verificação de Agendamentos / Blocos de Horário ativos (com suporte a lista por horário)
        if (!isScheduleSnoozed()) {
            val activeSchedules = getSchedules().filter { it.isActiveNow() }
            for (schedule in activeSchedules) {
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
    private var lastCacheTimeMillis: Long = 0L

    private fun getDynamicSystemAllowedPackages(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastCacheTimeMillis < 60_000L && cachedAllowedSysPackages.isNotEmpty()) {
            return cachedAllowedSysPackages
        }
        val result = mutableSetOf<String>()
        try {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { result.add(it.packageName) }
        } catch (_: Exception) {
        }
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = appContext.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName?.let { result.add(it) }
        } catch (_: Exception) {
        }
        cachedAllowedSysPackages = result
        lastCacheTimeMillis = now
        return result
    }

    fun isAlwaysAllowed(packageName: String): Boolean {
        val lower = packageName.lowercase()

        // WhatsApp (padrão e business)
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") return true

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
        if (lower.contains("dialer") || lower.contains("telecom") ||
            lower.contains("phone") || lower.contains("incall") ||
            lower.contains("emergency")
        ) return true

        // UI do sistema, configurações do Android (para permitir ativar acessibilidade/NFC) e instalador/permissões
        if (packageName == "com.android.systemui" ||
            packageName == "android" ||
            packageName == "com.android.settings" ||
            lower.contains("permissioncontroller") ||
            lower.contains("packageinstaller") ||
            lower.contains("securitycenter")
        ) return true

        // Teclados / IMEs e Launchers por nome
        if (lower.contains("inputmethod") || lower.contains("keyboard") ||
            lower.contains("gboard") || lower.contains("honeyboard") ||
            lower.contains("swiftkey") || lower.contains("latinime") ||
            lower.contains("launcher") || lower.contains("home") || lower.contains("trebuchet")
        ) return true

        if (getDynamicSystemAllowedPackages().contains(packageName)) {
            return true
        }

        return false
    }

    companion object {
        const val MODE_NORMAL = "NORMAL"
        const val MODE_MINDFUL_DELAY = "MINDFUL_DELAY"
        const val MODE_DEEP_FOCUS_NFC = "DEEP_FOCUS_NFC"
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
        private const val KEY_STRICTNESS_MODE = "strictness_mode"
        private const val KEY_ANTI_TAMPER = "anti_tamper_enabled"
        private const val KEY_BLOCK_SHORTS_REELS = "block_shorts_reels"
        private const val KEY_COGNITIVE_CHALLENGE = "cognitive_challenge"
        private const val KEY_CUSTOM_LOCK_QUOTE = "custom_lock_quote"
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
