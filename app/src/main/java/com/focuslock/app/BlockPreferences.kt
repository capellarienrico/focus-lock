package com.focuslock.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray

class BlockPreferences(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)

    var isMasterPasswordConfigured: Boolean
        get() = prefs.getBoolean(KEY_PASSWORD_CONFIGURED, false)
        set(value) = prefs.edit().putBoolean(KEY_PASSWORD_CONFIGURED, value).commit()

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
        get() = prefs.getString(KEY_NFC_TAG, null)
        set(value) {
            prefs.edit().putString(KEY_NFC_TAG, value).commit()
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

        val blockedList = getBlockedPackages()

        // 2. Bloqueio Manual ativo
        if (isManualLockActive) {
            return blockedList.isEmpty() || blockedList.contains(packageName)
        }

        // 3. Verificação de Agendamentos / Blocos de Horário ativos
        if (isAnyScheduleActiveNow()) {
            return blockedList.isEmpty() || blockedList.contains(packageName)
        }

        return false
    }

    fun isAlwaysAllowed(packageName: String): Boolean {
        val lower = packageName.lowercase()

        // WhatsApp (padrão e business)
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") return true

        // O próprio aplicativo FocusLock
        if (packageName == appContext.packageName || packageName == "com.focuslock.app") return true

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

        // Teclados / IMEs (para que o usuário sempre consiga digitar a Senha Mestre)
        if (lower.contains("inputmethod") || lower.contains("keyboard") ||
            lower.contains("gboard") || lower.contains("honeyboard") ||
            lower.contains("swiftkey") || lower.contains("latinime")
        ) return true

        try {
            val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val enabledImes = imm?.enabledInputMethodList?.map { it.packageName } ?: emptyList()
            if (enabledImes.contains(packageName)) return true
        } catch (_: Exception) {
        }

        // Launchers (Tela Inicial do Android)
        if (lower.contains("launcher") || lower.contains("home") || lower.contains("trebuchet")) {
            return true
        }

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = appContext.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultLauncher = resolveInfo?.activityInfo?.packageName
            if (defaultLauncher != null && defaultLauncher == packageName) {
                return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    companion object {
        private const val KEY_PASSWORD_CONFIGURED = "password_configured"
        private const val KEY_MANUAL_LOCK = "manual_lock"
        private const val KEY_SCHEDULE_SNOOZE_UNTIL = "schedule_snooze_until"
        private const val KEY_NFC_TAG = "nfc_tag_id"
        private const val KEY_MASTER_PIN = "master_pin"
        private const val KEY_SCHEDULES = "schedules_json"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        private const val KEY_BLOCKED_PACKAGES_JSON = "blocked_packages_json"
    }
}
