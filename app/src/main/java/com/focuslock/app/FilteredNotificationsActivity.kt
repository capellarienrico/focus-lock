package com.focuslock.app

import android.app.ActivityOptions
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focuslock.app.databinding.ActivityFilteredNotificationsBinding
import com.focuslock.app.databinding.ItemFilteredNotificationBinding
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilteredNotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilteredNotificationsBinding
    private lateinit var store: NotificationFilterStore
    private lateinit var adapter: FilteredNotificationsAdapter
    private val iconCache = LruCache<String, Drawable>(80)

    private var allItems: List<FilteredNotificationItem> = emptyList()
    private var displayedItems: List<FilteredNotificationItem> = emptyList()
    private var selectedFilterPackage: String? = null

    private val storeListener: () -> Unit = {
        reloadFromStore()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilteredNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = NotificationFilterStore.getInstance(this)
        adapter = FilteredNotificationsAdapter()
        binding.rvFilteredNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvFilteredNotifications.adapter = adapter

        binding.btnBackInbox.setOnClickListener {
            finish()
        }

        binding.btnClearAllInbox.setOnClickListener {
            val pkgFilter = selectedFilterPackage
            if (pkgFilter != null) {
                store.clearPackage(pkgFilter)
                selectedFilterPackage = null
            } else {
                store.clearAll()
            }
            FocusNotificationListenerService.syncSummaryNotification(this)
            reloadFromStore()
        }

        reloadFromStore()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        store.addListener(storeListener)
        reloadFromStore()
    }

    override fun onPause() {
        isResumed = false
        store.removeListener(storeListener)
        super.onPause()
    }

    private fun reloadFromStore() {
        allItems = store.getNotifications()

        if (selectedFilterPackage != null && allItems.none { it.packageName == selectedFilterPackage }) {
            selectedFilterPackage = null
        }

        displayedItems = if (selectedFilterPackage == null) {
            allItems
        } else {
            allItems.filter { it.packageName == selectedFilterPackage }
        }

        val count = allItems.size
        binding.tvInboxTitle.text = "🔕 Notificações Filtradas ($count)"
        binding.btnClearAllInbox.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.btnClearAllInbox.text = if (selectedFilterPackage != null) {
            "Limpar App"
        } else {
            "Limpar Tudo"
        }

        renderAppFilterChips()
        adapter.notifyDataSetChanged()

        if (displayedItems.isEmpty()) {
            binding.rvFilteredNotifications.visibility = View.GONE
            binding.llInboxEmpty.visibility = View.VISIBLE
        } else {
            binding.llInboxEmpty.visibility = View.GONE
            binding.rvFilteredNotifications.visibility = View.VISIBLE
        }
    }

    private fun renderAppFilterChips() {
        binding.llInboxAppChips.removeAllViews()
        if (allItems.isEmpty()) {
            binding.hsvAppFilterChips.visibility = View.GONE
            return
        }
        binding.hsvAppFilterChips.visibility = View.VISIBLE

        addFilterChip(
            label = "Todos (${allItems.size})",
            isSelected = selectedFilterPackage == null,
            onClick = {
                selectedFilterPackage = null
                reloadFromStore()
            }
        )

        val grouped = allItems.groupBy { it.packageName }
            .entries
            .sortedByDescending { it.value.size }

        grouped.forEach { (pkg, list) ->
            val appName = list.first().appName
            addFilterChip(
                label = "$appName (${list.size})",
                isSelected = selectedFilterPackage == pkg,
                onClick = {
                    selectedFilterPackage = pkg
                    reloadFromStore()
                }
            )
        }
    }

    private fun addFilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
        val chip = MaterialButton(
            this,
            null,
            if (isSelected) {
                com.google.android.material.R.attr.materialButtonStyle
            } else {
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            }
        ).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            minHeight = dpToPx(34)
            minimumHeight = dpToPx(34)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dpToPx(17)
            if (isSelected) {
                backgroundTintList = ContextCompat.getColorStateList(
                    this@FilteredNotificationsActivity,
                    R.color.accent_blue
                )
                setTextColor(
                    ContextCompat.getColor(this@FilteredNotificationsActivity, R.color.text_white)
                )
            } else {
                setTextColor(
                    ContextCompat.getColor(
                        this@FilteredNotificationsActivity,
                        R.color.text_secondary
                    )
                )
                strokeColor = ContextCompat.getColorStateList(
                    this@FilteredNotificationsActivity,
                    R.color.card_border
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(34)
            ).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener { onClick() }
        }
        binding.llInboxAppChips.addView(chip)
    }

    private fun openNotificationOrigin(item: FilteredNotificationItem) {
        val cachedPi = store.getPendingIntent(item.id)
        var opened = false

        if (cachedPi != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    cachedPi.send(this, 0, null, null, null, null, options.toBundle())
                } else {
                    cachedPi.send()
                }
                opened = true
            } catch (_: Exception) {
                opened = false
            }
        }

        if (!opened) {
            val launchIntent = packageManager.getLaunchIntentForPackage(item.packageName)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    opened = true
                } catch (_: Exception) {
                }
            }
        }

        store.removeNotification(item.id)
        FocusNotificationListenerService.syncSummaryNotification(this)

        if (!opened) {
            Toast.makeText(this, "Não foi possível abrir ${item.appName}.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTimestamp(ts: Long): String {
        val now = System.currentTimeMillis()
        val diffMin = ((now - ts).coerceAtLeast(0L)) / 60_000L
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        val ago = when {
            diffMin < 1L -> "agora"
            diffMin < 60L -> "há ${diffMin}m"
            diffMin < 1440L -> "há ${diffMin / 60L}h"
            else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(ts))
        }
        return "$clock • $ago"
    }

    private fun getAppIcon(pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return it }
        return try {
            val icon = packageManager.getApplicationIcon(pkg)
            iconCache.put(pkg, icon)
            icon
        } catch (_: Exception) {
            ContextCompat.getDrawable(this, R.drawable.ic_focus_shield)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private inner class FilteredNotificationsAdapter :
        RecyclerView.Adapter<FilteredNotificationsAdapter.NotifViewHolder>() {

        inner class NotifViewHolder(val itemBinding: ItemFilteredNotificationBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotifViewHolder {
            val b = ItemFilteredNotificationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return NotifViewHolder(b)
        }

        override fun onBindViewHolder(holder: NotifViewHolder, position: Int) {
            val item = displayedItems[position]
            val b = holder.itemBinding

            b.ivNotifAppIcon.setImageDrawable(getAppIcon(item.packageName))
            b.tvNotifAppName.text = item.appName
            b.tvNotifTime.text = formatTimestamp(item.timestamp)
            b.tvNotifTitle.text = item.title.ifBlank { item.appName }

            if (item.text.isBlank()) {
                b.tvNotifBody.visibility = View.GONE
            } else {
                b.tvNotifBody.visibility = View.VISIBLE
                b.tvNotifBody.text = item.text
            }

            b.btnDismissNotif.setOnClickListener {
                store.removeNotification(item.id)
                FocusNotificationListenerService.syncSummaryNotification(this@FilteredNotificationsActivity)
                reloadFromStore()
            }

            b.root.setOnClickListener {
                openNotificationOrigin(item)
            }
        }

        override fun getItemCount(): Int = displayedItems.size
    }

    companion object {
        @Volatile
        var isResumed: Boolean = false
            private set
    }
}
