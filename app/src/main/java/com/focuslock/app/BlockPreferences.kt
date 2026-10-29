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
        get() = prefs.getBoolean(KEY_ANTI_TAMPER, false)
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

    var isMonkModeOnTimerEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONK_MODE_TIMER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MONK_MODE_TIMER, value).commit()
        }

    var customLockQuote: String
        get() = prefs.getString(KEY_CUSTOM_LOCK_QUOTE, DEFAULT_LOCK_QUOTE) ?: DEFAULT_LOCK_QUOTE
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_LOCK_QUOTE, value).commit()
        }

    // =========================================================================
    // Sessão Rápida de Foco / Pomodoro Timer
    // =========================================================================

    var timedFocusUntilMillis: Long
        get() = prefs.getLong(KEY_TIMED_FOCUS_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_TIMED_FOCUS_UNTIL, value).commit()
        }

    var timedFocusDurationMinutes: Int
        get() = prefs.getInt(KEY_TIMED_FOCUS_DURATION_MINS, 25)
        set(value) {
            prefs.edit().putInt(KEY_TIMED_FOCUS_DURATION_MINS, value).commit()
        }

    fun startTimedFocusMinutes(minutes: Int) {
        val until = System.currentTimeMillis() + (minutes * 60_000L)
        prefs.edit()
            .putLong(KEY_TIMED_FOCUS_UNTIL, until)
            .putInt(KEY_TIMED_FOCUS_DURATION_MINS, minutes)
            .putLong(KEY_SCHEDULE_SNOOZE_UNTIL, 0L)
            .putLong(KEY_TIME_LIMIT_SNOOZE_UNTIL, 0L)
            .commit()
    }

    fun stopTimedFocus() {
        timedFocusUntilMillis = 0L
    }

    fun isTimedFocusActive(): Boolean {
        return System.currentTimeMillis() < timedFocusUntilMillis
    }

    fun getTimedFocusRemainingMillis(): Long {
        return (timedFocusUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // =========================================================================
    // Limite Diário de Tempo de Tela por App (Estilo Minimalist Phone / Wellbeing)
    // =========================================================================

    var isDailyTimeLimitEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_TIME_LIMIT_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DAILY_TIME_LIMIT_ENABLED, value).commit()
        }

    var dailyTimeLimitMinutes: Int
        get() = prefs.getInt(KEY_DAILY_TIME_LIMIT_MINUTES, 30)
        set(value) {
            prefs.edit().putInt(KEY_DAILY_TIME_LIMIT_MINUTES, value).commit()
        }

    var timeLimitSnoozeUntilMillis: Long
        get() = prefs.getLong(KEY_TIME_LIMIT_SNOOZE_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_TIME_LIMIT_SNOOZE_UNTIL, value).commit()
        }

    fun snoozeTimeLimitForMinutes(minutes: Int) {
        timeLimitSnoozeUntilMillis = System.currentTimeMillis() + (minutes * 60_000L)
    }

    fun isTimeLimitSnoozed(): Boolean {
        return System.currentTimeMillis() < timeLimitSnoozeUntilMillis
    }

    fun getTimeLimitedPackages(): Set<String> {
        return readStringSetJson(KEY_TIME_LIMITED_PACKAGES_JSON)
    }

    fun saveTimeLimitedPackages(packages: Set<String>) {
        writeStringSetJson(KEY_TIME_LIMITED_PACKAGES_JSON, packages)
    }

    // =========================================================================
    // Filtro de Notificações (Estilo Minimalist Phone)
    // =========================================================================

    var isNotificationFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_FILTER_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIF_FILTER_ENABLED, value).commit()
        }

    var notificationFilterMode: String
        get() = prefs.getString(KEY_NOTIF_FILTER_MODE, NOTIF_MODE_ALWAYS) ?: NOTIF_MODE_ALWAYS
        set(value) {
            prefs.edit().putString(KEY_NOTIF_FILTER_MODE, value).commit()
        }

    var floatingPillMode: String
        get() = prefs.getString(KEY_FLOATING_PILL_MODE, PILL_MODE_HOME_ONLY) ?: PILL_MODE_HOME_ONLY
        set(value) {
            prefs.edit().putString(KEY_FLOATING_PILL_MODE, value).commit()
        }

    var floatingPillYOffsetPx: Int
        get() = prefs.getInt(KEY_FLOATING_PILL_Y_OFFSET, 240)
        set(value) {
            prefs.edit().putInt(KEY_FLOATING_PILL_Y_OFFSET, value).commit()
        }

    fun getFilteredNotificationPackages(): Set<String> {
        return readStringSetJson(KEY_FILTERED_NOTIF_PACKAGES_JSON)
    }

    fun saveFilteredNotificationPackages(packages: Set<String>) {
        writeStringSetJson(KEY_FILTERED_NOTIF_PACKAGES_JSON, packages)
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
                editor.putLong(KEY_TIME_LIMIT_SNOOZE_UNTIL, 0L)
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
                daysOfWeek = listOf(1, 2, 3, 4, 5, 6, 7),
                isEnabled = false
            )
        )
    }

    fun getBlockedPackages(): Set<String> {
        val jsonStr = prefs.getString(KEY_BLOCKED_PACKAGES_JSON, null)
        if (jsonStr != null) {
            return readStringSetJson(KEY_BLOCKED_PACKAGES_JSON)
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

    private fun readStringSetJson(key: String): Set<String> {
        val jsonStr = prefs.getString(key, null) ?: return emptySet()
        return try {
            val arr = JSONArray(jsonStr)
            val set = mutableSetOf<String>()
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
        prefs.edit().putString(key, arr.toString()).commit()
    }

    fun isAnyScheduleActiveNow(): Boolean {
        if (isScheduleSnoozed()) return false
        return getSchedules().any { it.isActiveNow() }
    }

    fun isBlockingCurrentlyActive(): Boolean {
        return isManualLockActive || isTimedFocusActive() || isAnyScheduleActiveNow()
    }

    /**
     * Determina se o pacote deve ser bloqueado no momento atual (bloqueio manual, timer ou horário).
     */
    fun shouldBlockNow(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        // 1. Apps que NUNCA podem ser bloqueados (WhatsApp, Bancos, Telefone, Teclado, Launcher, FocusLock)
        if (isAlwaysAllowed(packageName)) {
            return false
        }

        val globalBlockedList = getBlockedPackages()

        // 2. Bloqueio Manual ou Sessão Pomodoro com Timer ativos
        if (isManualLockActive || isTimedFocusActive()) {
            if (globalBlockedList.isEmpty() || globalBlockedList.contains(packageName)) {
                return true
            }
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
        root.put("monkModeTimer", isMonkModeOnTimerEnabled)
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

        return root.toString(2)
    }

    fun importBackupJson(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            strictnessMode = root.optString("strictnessMode", strictnessMode)
            isAntiTamperEnabled = root.optBoolean("antiTamper", isAntiTamperEnabled)
            isCognitiveChallengeEnabled = root.optBoolean("cognitiveChallenge", isCognitiveChallengeEnabled)
            isMonkModeOnTimerEnabled = root.optBoolean("monkModeTimer", isMonkModeOnTimerEnabled)
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
        private const val KEY_STRICTNESS_MODE = "strictness_mode"
        private const val KEY_ANTI_TAMPER = "anti_tamper_v31_enabled"
        private const val KEY_BLOCK_SHORTS_REELS = "block_shorts_reels"
        private const val KEY_COGNITIVE_CHALLENGE = "cognitive_challenge"
        private const val KEY_MONK_MODE_TIMER = "monk_mode_timer"
        private const val KEY_CUSTOM_LOCK_QUOTE = "custom_lock_quote"
        private const val KEY_TIMED_FOCUS_UNTIL = "timed_focus_until"
        private const val KEY_TIMED_FOCUS_DURATION_MINS = "timed_focus_duration_mins"
        private const val KEY_DAILY_TIME_LIMIT_ENABLED = "daily_time_limit_enabled"
        private const val KEY_DAILY_TIME_LIMIT_MINUTES = "daily_time_limit_minutes"
        private const val KEY_TIME_LIMIT_SNOOZE_UNTIL = "time_limit_snooze_until"
        private const val KEY_TIME_LIMITED_PACKAGES_JSON = "time_limited_packages_json"
        private const val KEY_NOTIF_FILTER_ENABLED = "notif_filter_enabled"
        private const val KEY_NOTIF_FILTER_MODE = "notif_filter_mode"
        private const val KEY_FLOATING_PILL_MODE = "floating_pill_mode"
        private const val KEY_FLOATING_PILL_Y_OFFSET = "floating_pill_y_offset"
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
