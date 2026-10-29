package com.focuslock.app

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class FilteredNotificationItem(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("pkg", packageName)
        put("app", appName)
        put("title", title)
        put("text", text)
        put("ts", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): FilteredNotificationItem {
            return FilteredNotificationItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                packageName = json.optString("pkg", ""),
                appName = json.optString("app", "App"),
                title = json.optString("title", ""),
                text = json.optString("text", ""),
                timestamp = json.optLong("ts", System.currentTimeMillis())
            )
        }
    }
}

class NotificationFilterStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("focus_lock_notif_inbox", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingIntentsMap = ConcurrentHashMap<String, PendingIntent>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()

    @Volatile
    private var cachedItems: MutableList<FilteredNotificationItem>? = null

    private fun ensureLoadedLocked(): MutableList<FilteredNotificationItem> {
        cachedItems?.let { return it }
        val loaded = mutableListOf<FilteredNotificationItem>()
        val raw = prefs.getString(KEY_ITEMS_JSON, null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val item = FilteredNotificationItem.fromJson(arr.getJSONObject(i))
                    if (item.packageName.isNotBlank()) {
                        loaded.add(item)
                    }
                }
            } catch (_: Exception) {
            }
        }
        cachedItems = loaded
        return loaded
    }

    private fun persistLocked(items: List<FilteredNotificationItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ITEMS_JSON, arr.toString()).apply()
    }

    fun getNotifications(): List<FilteredNotificationItem> {
        synchronized(lock) {
            return ensureLoadedLocked().toList()
        }
    }

    fun getActiveCount(): Int {
        synchronized(lock) {
            return ensureLoadedLocked().size
        }
    }

    fun addFilteredNotification(
        packageName: String,
        appName: String,
        title: String,
        text: String,
        timestamp: Long,
        contentIntent: PendingIntent?
    ): Boolean {
        val cleanTitle = title.trim()
        val cleanText = text.trim()
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) return false

        synchronized(lock) {
            val list = ensureLoadedLocked()
            val duplicateIdx = list.indexOfFirst { existing ->
                existing.packageName == packageName &&
                        existing.title == cleanTitle &&
                        existing.text == cleanText &&
                        kotlin.math.abs(timestamp - existing.timestamp) < 4000L
            }
            if (duplicateIdx >= 0) {
                if (contentIntent != null) {
                    pendingIntentsMap[list[duplicateIdx].id] = contentIntent
                }
                return false
            }

            val id = UUID.randomUUID().toString()
            val newItem = FilteredNotificationItem(
                id = id,
                packageName = packageName,
                appName = appName,
                title = cleanTitle,
                text = cleanText,
                timestamp = timestamp
            )
            if (contentIntent != null) {
                pendingIntentsMap[id] = contentIntent
            }

            list.add(0, newItem)
            while (list.size > MAX_STORED_NOTIFICATIONS) {
                val removed = list.removeAt(list.lastIndex)
                pendingIntentsMap.remove(removed.id)
            }
            persistLocked(list)
            incrementTodayCounterLocked()
        }

        notifyListeners()
        return true
    }

    fun removeNotification(id: String) {
        synchronized(lock) {
            val list = ensureLoadedLocked()
            val changed = list.removeAll { it.id == id }
            pendingIntentsMap.remove(id)
            if (changed) {
                persistLocked(list)
            }
        }
        notifyListeners()
    }

    fun clearPackage(packageName: String) {
        synchronized(lock) {
            val list = ensureLoadedLocked()
            val toRemove = list.filter { it.packageName == packageName }
            if (toRemove.isNotEmpty()) {
                toRemove.forEach { pendingIntentsMap.remove(it.id) }
                list.removeAll { it.packageName == packageName }
                persistLocked(list)
            }
        }
        notifyListeners()
    }

    fun clearAll() {
        synchronized(lock) {
            val list = ensureLoadedLocked()
            list.clear()
            pendingIntentsMap.clear()
            persistLocked(list)
        }
        notifyListeners()
    }

    fun getPendingIntent(id: String): PendingIntent? {
        return pendingIntentsMap[id]
    }

    fun getFilteredCountToday(): Int {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "") ?: ""
        if (savedDate != today) return 0
        return prefs.getInt(KEY_TODAY_COUNT, 0)
    }

    private fun incrementTodayCounterLocked() {
        val today = currentDateKey()
        val savedDate = prefs.getString(KEY_TODAY_DATE, "") ?: ""
        val current = if (savedDate == today) prefs.getInt(KEY_TODAY_COUNT, 0) else 0
        prefs.edit()
            .putString(KEY_TODAY_DATE, today)
            .putInt(KEY_TODAY_COUNT, current + 1)
            .apply()
    }

    private fun currentDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            listeners.forEach { it.invoke() }
        }
    }

    companion object {
        private const val MAX_STORED_NOTIFICATIONS = 150
        private const val KEY_ITEMS_JSON = "filtered_items_json"
        private const val KEY_TODAY_DATE = "filtered_today_date"
        private const val KEY_TODAY_COUNT = "filtered_today_count"

        @Volatile
        private var instance: NotificationFilterStore? = null

        fun getInstance(context: Context): NotificationFilterStore {
            return instance ?: synchronized(this) {
                instance ?: NotificationFilterStore(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
