package com.focuslock.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focuslock.app.databinding.ActivityAppPickerBinding
import com.focuslock.app.databinding.ItemAppPickerBinding
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.util.concurrent.Executors

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var prefs: BlockPreferences
    private val selectedPackages = LinkedHashSet<String>()
    private var allApps: List<AppPickerItem> = emptyList()
    private var filteredApps: List<AppPickerItem> = emptyList()

    private var activeCategory: AppCategory = AppCategory.ALL
    private var activeSortOrder: AppSortOrder = AppSortOrder.SCREEN_TIME_TODAY
    private var searchQuery: String = ""

    private var allowEmptyMeansAll: Boolean = false
    private var emptyButtonLabel: String = "Limpar Tudo"
    private var includeWhatsApp: Boolean = false
    private var pickerModeTag: String = ""

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val iconCache = LruCache<String, Drawable>(120)
    private lateinit var adapter: AppPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as FocusLockApp).prefs

        pickerModeTag = intent.getStringExtra(EXTRA_PICKER_MODE) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Selecionar Aplicativos"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
            ?: "Categorize ou ordene por tempo de tela, tamanho e nome"
        allowEmptyMeansAll = intent.getBooleanExtra(EXTRA_ALLOW_EMPTY_MEANS_ALL, false)
        emptyButtonLabel = intent.getStringExtra(EXTRA_EMPTY_BUTTON_LABEL)
            ?: if (allowEmptyMeansAll) "Bloquear Todos" else "Limpar Tudo"
        includeWhatsApp = intent.getBooleanExtra(EXTRA_INCLUDE_WHATSAPP, false)

        intent.getStringArrayListExtra(EXTRA_INITIAL_SELECTED)?.let {
            selectedPackages.addAll(it)
        }

        binding.tvPickerTitle.text = title
        binding.tvPickerSubtitle.text = subtitle
        binding.btnSecondaryAction.text = emptyButtonLabel

        adapter = AppPickerAdapter()
        binding.rvPickerApps.layoutManager = LinearLayoutManager(this)
        binding.rvPickerApps.adapter = adapter

        setupListeners()
        updateCountersUI()
        loadAppsAsync()
    }

    private fun setupListeners() {
        binding.btnBackPicker.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFiltersAndSort()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSortOrder.setOnClickListener {
            val orders = AppSortOrder.values()
            val labels = orders.map { it.labelPt }.toTypedArray()
            val currentIdx = orders.indexOf(activeSortOrder).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle("Ordenar Aplicativos Por")
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    activeSortOrder = orders[which]
                    binding.btnSortOrder.text = "Ordenar: ${activeSortOrder.labelPt} ▾"
                    applyFiltersAndSort()
                    dialog.dismiss()
                }
                .setNegativeButton("Fechar", null)
                .show()
        }

        binding.btnToggleVisibleApps.setOnClickListener {
            if (filteredApps.isEmpty()) return@setOnClickListener
            val allVisibleSelected = filteredApps.all { selectedPackages.contains(it.packageName) }
            if (allVisibleSelected) {
                filteredApps.forEach { selectedPackages.remove(it.packageName) }
            } else {
                filteredApps.forEach { selectedPackages.add(it.packageName) }
            }
            adapter.notifyDataSetChanged()
            updateCountersUI()
        }

        binding.btnSecondaryAction.setOnClickListener {
            if (allowEmptyMeansAll) {
                selectedPackages.clear()
                returnResultAndFinish()
            } else {
                selectedPackages.clear()
                adapter.notifyDataSetChanged()
                updateCountersUI()
            }
        }

        binding.btnSaveSelection.setOnClickListener {
            returnResultAndFinish()
        }
    }

    private fun returnResultAndFinish() {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_PICKER_MODE, pickerModeTag)
            putStringArrayListExtra(EXTRA_RESULT_PACKAGES, ArrayList(selectedPackages))
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun loadAppsAsync() {
        binding.llPickerLoading.visibility = View.VISIBLE
        binding.rvPickerApps.visibility = View.GONE
        binding.tvPickerEmpty.visibility = View.GONE

        bgExecutor.execute {
            val loaded = AppMetadataHelper.loadInstalledPickerApps(
                context = applicationContext,
                prefs = prefs,
                includeWhatsApp = includeWhatsApp
            )
            // Se não houver nenhum dado de tempo de tela hoje ainda, usa Nome A-Z ou 7 dias como padrão amigável
            val hasTodayUsage = loaded.any { it.usageTodayMs > 0L }
            val has7dUsage = loaded.any { it.usage7DaysMs > 0L }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = loaded
                if (!hasTodayUsage) {
                    activeSortOrder = if (has7dUsage) {
                        AppSortOrder.SCREEN_TIME_7D
                    } else {
                        AppSortOrder.NAME_ASC
                    }
                }
                binding.btnSortOrder.text = "Ordenar: ${activeSortOrder.labelPt} ▾"
                binding.llPickerLoading.visibility = View.GONE
                renderCategoryChips()
                applyFiltersAndSort()
            }
        }
    }

    private fun renderCategoryChips() {
        binding.llCategoryChips.removeAllViews()
        val countsByCat = allApps.groupingBy { it.category }.eachCount()

        AppCategory.values().forEach { cat ->
            val count = if (cat == AppCategory.ALL) allApps.size else (countsByCat[cat] ?: 0)
            if (cat != AppCategory.ALL && count == 0) return@forEach

            val isSelected = cat == activeCategory
            val chipBtn = MaterialButton(
                this,
                null,
                if (isSelected) {
                    com.google.android.material.R.attr.materialButtonStyle
                } else {
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                }
            ).apply {
                text = cat.chipTitle(count)
                isAllCaps = false
                textSize = 11f
                minHeight = dpToPx(34)
                minimumHeight = dpToPx(34)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dpToPx(17)
                if (isSelected) {
                    backgroundTintList = ContextCompat.getColorStateList(this@AppPickerActivity, R.color.accent_blue)
                    setTextColor(ContextCompat.getColor(this@AppPickerActivity, R.color.text_white))
                } else {
                    setTextColor(ContextCompat.getColor(this@AppPickerActivity, R.color.text_secondary))
                    strokeColor = ContextCompat.getColorStateList(this@AppPickerActivity, R.color.card_border)
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(34)
                ).apply {
                    marginEnd = dpToPx(6)
                }
                layoutParams = params

                setOnClickListener {
                    activeCategory = cat
                    renderCategoryChips()
                    applyFiltersAndSort()
                }
            }
            binding.llCategoryChips.addView(chipBtn)
        }
    }

    private fun applyFiltersAndSort() {
        val q = searchQuery.lowercase(Locale.ROOT)
        var list = allApps.filter { item ->
            val matchesCat = activeCategory == AppCategory.ALL || item.category == activeCategory
            val matchesQuery = q.isEmpty() ||
                    item.label.lowercase(Locale.ROOT).contains(q) ||
                    item.packageName.lowercase(Locale.ROOT).contains(q)
            matchesCat && matchesQuery
        }

        val nameComparator = compareBy<AppPickerItem> { it.label.lowercase(Locale.ROOT) }
        list = when (activeSortOrder) {
            AppSortOrder.SCREEN_TIME_TODAY -> list.sortedWith(
                compareByDescending<AppPickerItem> { it.usageTodayMs }
                    .thenByDescending { it.usage7DaysMs }
                    .then(nameComparator)
            )
            AppSortOrder.SCREEN_TIME_7D -> list.sortedWith(
                compareByDescending<AppPickerItem> { it.usage7DaysMs }
                    .thenByDescending { it.usageTodayMs }
                    .then(nameComparator)
            )
            AppSortOrder.NAME_ASC -> list.sortedWith(nameComparator)
            AppSortOrder.NAME_DESC -> list.sortedWith(
                compareByDescending<AppPickerItem> { it.label.lowercase(Locale.ROOT) }
            )
            AppSortOrder.SIZE_DESC -> list.sortedWith(
                compareByDescending<AppPickerItem> { it.sizeBytes }.then(nameComparator)
            )
            AppSortOrder.INSTALL_RECENT -> list.sortedWith(
                compareByDescending<AppPickerItem> { it.firstInstallTime }.then(nameComparator)
            )
            AppSortOrder.SELECTED_FIRST -> list.sortedWith(
                compareByDescending<AppPickerItem> { selectedPackages.contains(it.packageName) }
                    .thenByDescending { it.usageTodayMs }
                    .then(nameComparator)
            )
        }

        filteredApps = list
        adapter.notifyDataSetChanged()
        binding.rvPickerApps.scrollToPosition(0)

        if (filteredApps.isEmpty()) {
            binding.rvPickerApps.visibility = View.GONE
            binding.tvPickerEmpty.visibility = View.VISIBLE
        } else {
            binding.tvPickerEmpty.visibility = View.GONE
            binding.rvPickerApps.visibility = View.VISIBLE
        }

        updateCountersUI()
    }

    private fun updateCountersUI() {
        val count = selectedPackages.size
        binding.tvPickerSelectedCount.text = "$count sel."
        binding.btnSaveSelection.text = if (count == 0 && allowEmptyMeansAll) {
            "SALVAR (TODOS OS APPS)"
        } else {
            "SALVAR SELEÇÃO ($count)"
        }

        val allVisibleSelected = filteredApps.isNotEmpty() &&
                filteredApps.all { selectedPackages.contains(it.packageName) }
        binding.btnToggleVisibleApps.text = if (allVisibleSelected) {
            "Desmarcar Visíveis"
        } else {
            "Marcar Visíveis"
        }
    }

    private fun getCachedAppIcon(packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }
        return try {
            val icon = packageManager.getApplicationIcon(packageName)
            iconCache.put(packageName, icon)
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

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    private inner class AppPickerAdapter : RecyclerView.Adapter<AppPickerAdapter.AppViewHolder>() {

        inner class AppViewHolder(val itemBinding: ItemAppPickerBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val b = ItemAppPickerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return AppViewHolder(b)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = filteredApps[position]
            val b = holder.itemBinding

            b.tvAppName.text = item.label
            b.tvAppCategory.text = "${item.category.emoji} ${item.category.labelPt}"

            val todayStr = AppMetadataHelper.formatDuration(item.usageTodayMs)
            val weekStr = AppMetadataHelper.formatDuration(item.usage7DaysMs)
            val sizeStr = AppMetadataHelper.formatBytes(item.sizeBytes)
            b.tvAppMetrics.text = "⏱️ $todayStr hoje • $weekStr (7d)  •  💾 $sizeStr"

            b.ivAppIcon.setImageDrawable(getCachedAppIcon(item.packageName))
            b.cbAppSelected.isChecked = selectedPackages.contains(item.packageName)

            b.root.setOnClickListener {
                if (selectedPackages.contains(item.packageName)) {
                    selectedPackages.remove(item.packageName)
                    b.cbAppSelected.isChecked = false
                } else {
                    selectedPackages.add(item.packageName)
                    b.cbAppSelected.isChecked = true
                }
                updateCountersUI()
            }
        }

        override fun getItemCount(): Int = filteredApps.size
    }

    companion object {
        const val EXTRA_PICKER_MODE = "EXTRA_PICKER_MODE"
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_SUBTITLE = "EXTRA_SUBTITLE"
        const val EXTRA_INITIAL_SELECTED = "EXTRA_INITIAL_SELECTED"
        const val EXTRA_ALLOW_EMPTY_MEANS_ALL = "EXTRA_ALLOW_EMPTY_MEANS_ALL"
        const val EXTRA_EMPTY_BUTTON_LABEL = "EXTRA_EMPTY_BUTTON_LABEL"
        const val EXTRA_INCLUDE_WHATSAPP = "EXTRA_INCLUDE_WHATSAPP"
        const val EXTRA_RESULT_PACKAGES = "EXTRA_RESULT_PACKAGES"

        const val MODE_GLOBAL_BLOCK = "GLOBAL_BLOCK"
        const val MODE_SCHEDULE_BLOCK = "SCHEDULE_BLOCK"
        const val MODE_NOTIFICATION_FILTER = "NOTIFICATION_FILTER"
        const val MODE_DAILY_TIME_LIMIT = "DAILY_TIME_LIMIT"
    }
}
