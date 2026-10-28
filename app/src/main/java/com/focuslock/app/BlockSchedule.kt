package com.focuslock.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class BlockSchedule(
    val id: String,
    val name: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val daysOfWeek: List<Int>, // Calendar.SUNDAY (1) .. SATURDAY (7)
    val isEnabled: Boolean = true,
    val customBlockedPackages: Set<String> = emptySet()
) {
    fun isActiveNow(): Boolean {
        if (!isEnabled) return false

        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK)
        val activeDays = if (daysOfWeek.isEmpty()) listOf(1, 2, 3, 4, 5, 6, 7) else daysOfWeek

        val currentMinuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinuteOfDay = startHour * 60 + startMinute
        val endMinuteOfDay = endHour * 60 + endMinute

        return if (startMinuteOfDay <= endMinuteOfDay) {
            activeDays.contains(currentDay) && (currentMinuteOfDay in startMinuteOfDay..endMinuteOfDay)
        } else {
            // Horário que cruza a meia-noite (ex: 22:00 às 06:00)
            if (currentMinuteOfDay >= startMinuteOfDay) {
                activeDays.contains(currentDay)
            } else if (currentMinuteOfDay <= endMinuteOfDay) {
                val yesterday = if (currentDay == Calendar.SUNDAY) Calendar.SATURDAY else currentDay - 1
                activeDays.contains(yesterday) || activeDays.contains(currentDay)
            } else {
                false
            }
        }
    }

    fun formatTimeRange(): String {
        return String.format("%02d:%02d às %02d:%02d", startHour, startMinute, endHour, endMinute)
    }

    fun formatDaysOfWeek(): String {
        if (daysOfWeek.size >= 7 || daysOfWeek.isEmpty()) return "Todos os dias"
        if (daysOfWeek.sorted() == listOf(2, 3, 4, 5, 6)) return "Seg a Sex"
        if (daysOfWeek.sorted() == listOf(1, 7)) return "Fim de semana"
        val map = mapOf(
            Calendar.SUNDAY to "Dom",
            Calendar.MONDAY to "Seg",
            Calendar.TUESDAY to "Ter",
            Calendar.WEDNESDAY to "Qua",
            Calendar.THURSDAY to "Qui",
            Calendar.FRIDAY to "Sex",
            Calendar.SATURDAY to "Sáb"
        )
        return daysOfWeek.sorted().mapNotNull { map[it] }.joinToString(", ")
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("startHour", startHour)
            put("startMinute", startMinute)
            put("endHour", endHour)
            put("endMinute", endMinute)
            put("daysOfWeek", JSONArray(daysOfWeek))
            put("isEnabled", isEnabled)
            put("customBlockedPackages", JSONArray(customBlockedPackages.toList()))
        }
    }

    companion object {
        fun fromJson(json: JSONObject): BlockSchedule {
            val daysJson = json.optJSONArray("daysOfWeek") ?: JSONArray()
            val days = mutableListOf<Int>()
            for (i in 0 until daysJson.length()) {
                days.add(daysJson.getInt(i))
            }
            val pkgsJson = json.optJSONArray("customBlockedPackages") ?: JSONArray()
            val customPkgs = mutableSetOf<String>()
            for (i in 0 until pkgsJson.length()) {
                val p = pkgsJson.optString(i)
                if (p.isNotBlank()) customPkgs.add(p)
            }
            return BlockSchedule(
                id = json.getString("id"),
                name = json.optString("name", "Bloco de Foco"),
                startHour = json.getInt("startHour"),
                startMinute = json.getInt("startMinute"),
                endHour = json.getInt("endHour"),
                endMinute = json.getInt("endMinute"),
                daysOfWeek = if (days.isEmpty()) listOf(1, 2, 3, 4, 5, 6, 7) else days,
                isEnabled = json.optBoolean("isEnabled", true),
                customBlockedPackages = customPkgs
            )
        }
    }
}
