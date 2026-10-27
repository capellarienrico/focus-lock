package com.focuslock.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class BlockPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)

    var isManualLockActive: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_LOCK, value).apply()

    var registeredNfcTagId: String?
        get() = prefs.getString(KEY_NFC_TAG, null)
        set(value) = prefs.edit().putString(KEY_NFC_TAG, value).apply()

    var masterPin: String
        get() = prefs.getString(KEY_MASTER_PIN, "1234") ?: "1234"
        set(value) = prefs.edit().putString(KEY_MASTER_PIN, value).apply()

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
        prefs.edit().putString(KEY_SCHEDULES, arr.toString()).apply()
    }

    private fun defaultSchedules(): MutableList<BlockSchedule> {
        return mutableListOf(
            BlockSchedule(
                id = "default_work",
                name = "Horário de Foco",
                startHour = 9,
                startMinute = 0,
                endHour = 18,
                endMinute = 0,
                daysOfWeek = listOf(2, 3, 4, 5, 6), // Seg a Sex
                isEnabled = false
            )
        )
    }

    fun getBlockedPackages(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun saveBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, packages).apply()
    }

    /**
     * Determina se o pacote deve ser bloqueado no momento atual.
     */
    fun shouldBlockNow(packageName: String): Boolean {
        // 1. Apps que NUNCA podem ser bloqueados (WhatsApp, Telefone, Sistema, FocusLock)
        if (isAlwaysAllowed(packageName)) {
            return false
        }

        // 2. Bloqueio Manual ativo (bloqueia tudo exceto os liberados)
        if (isManualLockActive) {
            return true
        }

        // 3. Verificação de Agendamentos / Blocos de Horário ativos
        val schedules = getSchedules()
        val isAnyScheduleActive = schedules.any { it.isActiveNow() }

        if (isAnyScheduleActive) {
            val blockedList = getBlockedPackages()
            // Se nenhuma lista específica foi configurada, bloqueia os apps gerais no horário
            return blockedList.isEmpty() || blockedList.contains(packageName)
        }

        return false
    }

    private fun isAlwaysAllowed(packageName: String): Boolean {
        // WhatsApp (padrão e business)
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") return true

        // O próprio aplicativo FocusLock
        if (packageName == "com.focuslock.app") return true

        // Discadores e telefone de emergência
        if (packageName.contains("dialer") || packageName.contains("telecom") ||
            packageName.contains("phone") || packageName.contains("incall")
        ) return true

        // UI do sistema e launchers (tela inicial do Android)
        if (packageName == "com.android.systemui" || packageName == "android" ||
            packageName.contains("launcher") || packageName.contains("home")
        ) return true

        return false
    }

    companion object {
        private const val KEY_MANUAL_LOCK = "manual_lock"
        private const val KEY_NFC_TAG = "nfc_tag_id"
        private const val KEY_MASTER_PIN = "master_pin"
        private const val KEY_SCHEDULES = "schedules_json"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    }
}
