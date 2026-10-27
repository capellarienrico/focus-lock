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
    val daysOfWeek: List<Int>, // Calendar.MONDAY (2), TUESDAY (3), etc.
    val isEnabled: Boolean = true
) {
    fun isActiveNow(): Boolean {
        if (!isEnabled) return false

        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK)

        if (!daysOfWeek.contains(currentDay)) {
            return false
        }

        val currentMinuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinuteOfDay = startHour * 60 + startMinute
        val endMinuteOfDay = endHour * 60 + endMinute

        return if (startMinuteOfDay <= endMinuteOfDay) {
            currentMinuteOfDay in startMinuteOfDay..endMinuteOfDay
        } else {
            // Horário que cruza a meia-noite (ex: 22:00 às 06:00)
            currentMinuteOfDay >= startMinuteOfDay || currentMinuteOfDay <= endMinuteOfDay
        }
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
        }
    }

    companion object {
        fun fromJson(json: JSONObject): BlockSchedule {
            val daysJson = json.optJSONArray("daysOfWeek") ?: JSONArray()
            val days = mutableListOf<Int>()
            for (i in 0 until daysJson.length()) {
                days.add(daysJson.getInt(i))
            }
            return BlockSchedule(
                id = json.getString("id"),
                name = json.getString("name"),
                startHour = json.getInt("startHour"),
                startMinute = json.getInt("startMinute"),
                endHour = json.getInt("endHour"),
                endMinute = json.getInt("endMinute"),
                daysOfWeek = days,
                isEnabled = json.optBoolean("isEnabled", true)
            )
        }
    }
}
