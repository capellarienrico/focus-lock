package com.focuslock.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.Calendar
import java.util.Locale

enum class AppCategory(val id: String, val labelPt: String, val emoji: String) {
    ALL("ALL", "Todos", "📋"),
    SOCIAL("SOCIAL", "Redes Sociais", "📱"),
    VIDEO("VIDEO", "Vídeo & Streaming", "🎬"),
    GAMES("GAMES", "Jogos", "🎮"),
    SHOPPING("SHOPPING", "Compras & Delivery", "🛒"),
    MUSIC("MUSIC", "Música & Áudio", "🎵"),
    NEWS_BROWSER("NEWS_BROWSER", "Navegadores & Leitura", "🌐"),
    PRODUCTIVITY("PRODUCTIVITY", "Produtividade & Outros", "💼");

    fun chipTitle(count: Int): String = "$emoji $labelPt ($count)"
}

enum class AppSortOrder(val labelPt: String) {
    SCREEN_TIME_TODAY("⏱️ Tempo de Tela (Hoje)"),
    SCREEN_TIME_7D("📊 Tempo de Tela (7 dias)"),
    NAME_ASC("🔤 Nome (A → Z)"),
    NAME_DESC("🔤 Nome (Z → A)"),
    SIZE_DESC("💾 Tamanho (Maior → Menor)"),
    INSTALL_RECENT("🆕 Instalado Recentemente"),
    SELECTED_FIRST("✅ Selecionados Primeiro")
}

data class AppPickerItem(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val sizeBytes: Long,
    val usageTodayMs: Long,
    val usage7DaysMs: Long,
    val firstInstallTime: Long
)

data class TopAppUsageSummary(
    val packageName: String,
    val label: String,
    val usageTodayMs: Long,
    val isCurrentlyInGlobalBlock: Boolean,
    val isAlwaysAllowed: Boolean
)

object AppMetadataHelper {

    fun getTodayMidnightMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Calcula o tempo real de tela de hoje (desde 00:00 no fuso local) a partir do fluxo cronológico
     * de eventos do UsageStatsManager (mesma abordagem do Bem-Estar Digital).
     */
    @Suppress("InlinedApi")
    fun queryTodayUsageMap(context: Context): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()

        val now = System.currentTimeMillis()
        val midnight = getTodayMidnightMillis()
        if (now <= midnight) return emptyMap()

        val totals = HashMap<String, Long>()
        return try {
            val events = usm.queryEvents(midnight, now)
            val event = UsageEvents.Event()
            var activePkg: String? = null
            var activeStart = 0L

            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                val ts = event.timeStamp
                val pkg = event.packageName ?: continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (activePkg != null && activePkg != pkg && activeStart > 0L && ts > activeStart) {
                            val delta = (ts - activeStart).coerceAtMost(4 * 3_600_000L)
                            totals[activePkg!!] = (totals[activePkg!!] ?: 0L) + delta
                        }
                        if (activePkg != pkg) {
                            activePkg = pkg
                            activeStart = ts
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (activePkg == pkg && activeStart > 0L && ts > activeStart) {
                            val delta = (ts - activeStart).coerceAtMost(4 * 3_600_000L)
                            totals[pkg] = (totals[pkg] ?: 0L) + delta
                            activePkg = null
                            activeStart = 0L
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.DEVICE_SHUTDOWN -> {
                        if (activePkg != null && activeStart > 0L && ts > activeStart) {
                            val delta = (ts - activeStart).coerceAtMost(4 * 3_600_000L)
                            totals[activePkg!!] = (totals[activePkg!!] ?: 0L) + delta
                        }
                        activePkg = null
                        activeStart = 0L
                    }
                }
            }

            // Se ainda há um app aberto neste exato segundo na tela interativa
            if (activePkg != null && activeStart > 0L && now > activeStart) {
                val delta = (now - activeStart).coerceAtMost(2 * 3_600_000L)
                totals[activePkg!!] = (totals[activePkg!!] ?: 0L) + delta
            }

            totals
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun query7DaysUsageMap(context: Context, todayMap: Map<String, Long>): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return todayMap
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - (7L * 24L * 3_600_000L)

        return try {
            val agg = usm.queryAndAggregateUsageStats(sevenDaysAgo, now)
            val result = HashMap<String, Long>(todayMap)
            agg?.forEach { (pkg, stat) ->
                val fg = stat.totalTimeInForeground
                val todayVal = todayMap[pkg] ?: 0L
                val combined = maxOf(fg, todayVal)
                if (combined > 0L) {
                    result[pkg] = combined
                }
            }
            result
        } catch (_: Exception) {
            todayMap
        }
    }

    fun loadInstalledPickerApps(
        context: Context,
        prefs: BlockPreferences,
        includeWhatsApp: Boolean
    ): List<AppPickerItem> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val todayUsage = queryTodayUsageMap(context)
        val weekUsage = query7DaysUsageMap(context, todayUsage)

        val apps = pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { app ->
                if (includeWhatsApp) {
                    !prefs.isBankingOrCriticalSystemPackage(app.packageName)
                } else {
                    !prefs.isAlwaysAllowed(app.packageName)
                }
            }

        return apps.map { appInfo ->
            val pkg = appInfo.packageName
            val label = try {
                pm.getApplicationLabel(appInfo).toString().trim().ifBlank { pkg }
            } catch (_: Exception) {
                pkg
            }
            val sizeBytes = computeAppSizeBytes(appInfo)
            val installTime = try {
                pm.getPackageInfo(pkg, 0).firstInstallTime
            } catch (_: Exception) {
                0L
            }

            AppPickerItem(
                packageName = pkg,
                label = label,
                category = categorizeApp(pkg, appInfo),
                sizeBytes = sizeBytes,
                usageTodayMs = todayUsage[pkg] ?: 0L,
                usage7DaysMs = weekUsage[pkg] ?: 0L,
                firstInstallTime = installTime
            )
        }
    }

    fun queryTodayDashboardSummary(
        context: Context,
        prefs: BlockPreferences,
        topLimit: Int = 5
    ): Pair<Long, List<TopAppUsageSummary>> {
        val pm = context.packageManager
        val todayMap = queryTodayUsageMap(context)
        if (todayMap.isEmpty()) return 0L to emptyList()

        val blockedGlobal = prefs.getBlockedPackages()
        var totalScreenTimeMs = 0L
        val validEntries = mutableListOf<TopAppUsageSummary>()

        for ((pkg, ms) in todayMap) {
            if (ms < 15_000L) continue
            if (prefs.isHomeLauncherPackage(pkg)) continue
            if (pkg == "com.android.systemui" || pkg == "android" || pkg.contains("inputmethod")) continue

            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                null
            } ?: continue

            totalScreenTimeMs += ms

            if (pkg == context.packageName) continue

            val label = try {
                pm.getApplicationLabel(appInfo).toString().trim().ifBlank { pkg }
            } catch (_: Exception) {
                pkg
            }

            val alwaysAllowed = prefs.isAlwaysAllowed(pkg)
            val isBlocked = !alwaysAllowed && (blockedGlobal.isEmpty() || blockedGlobal.contains(pkg))

            validEntries.add(
                TopAppUsageSummary(
                    packageName = pkg,
                    label = label,
                    usageTodayMs = ms,
                    isCurrentlyInGlobalBlock = isBlocked,
                    isAlwaysAllowed = alwaysAllowed
                )
            )
        }

        val topList = validEntries
            .sortedByDescending { it.usageTodayMs }
            .take(topLimit)

        return totalScreenTimeMs to topList
    }

    private fun computeAppSizeBytes(appInfo: ApplicationInfo): Long {
        var total = 0L
        try {
            appInfo.sourceDir?.let { path ->
                val f = File(path)
                if (f.exists()) total += f.length()
            }
            appInfo.splitSourceDirs?.forEach { splitPath ->
                val f = File(splitPath)
                if (f.exists()) total += f.length()
            }
        } catch (_: Exception) {
        }
        return total
    }

    fun categorizeApp(packageName: String, appInfo: ApplicationInfo): AppCategory {
        val p = packageName.lowercase(Locale.ROOT)

        // 1. Compras & Delivery
        val shoppingMarkers = listOf(
            "shopee", "mercadolivre", "mercadolibre", "amazon.mshop", "aliexpress",
            "shein", "magazineluiza", "magalu", "americanas", "ifood", "rappi",
            "ubercab", "taxis99", "99app", "olx", "enjoei", "zedelivery", "ze.delivery",
            "aiqfome", "casasbahia", "netshoes", "dafiti"
        )
        if (shoppingMarkers.any { p.contains(it) }) return AppCategory.SHOPPING

        // 2. Música & Áudio (checado antes de vídeo por causa do YouTube Music)
        val musicMarkers = listOf(
            "youtube.music", "apple.android.music", "spotify", "deezer", "soundcloud",
            "audible", "tidal", "bandcamp", "pocketcasts", "castbox", "tunein", "shazam"
        )
        if (musicMarkers.any { p.contains(it) }) return AppCategory.MUSIC

        // 3. Redes Sociais & Mensageiros
        val socialMarkers = listOf(
            "instagram", "facebook", "katana", "orca", "zhiliaoapp", "musically",
            "tiktok", "ss.android.ugc", "twitter", "com.x.", "barcelona", "threads",
            "snapchat", "reddit", "pinterest", "linkedin", "discord", "telegram",
            "whatsapp", "tumblr", "bereal", "kwai", "tinder", "bumble", "badoo"
        )
        if (socialMarkers.any { p.contains(it) }) return AppCategory.SOCIAL

        // 4. Vídeo & Streaming
        val videoMarkers = listOf(
            "youtube", "netflix", "twitch", "avod.thirdpartyclient", "primevideo",
            "disney", "hbo", "wbd.stream", "crunchyroll", "globoplay", "stremio",
            "plex", "videolan.vlc", "starplus", "paramount"
        )
        if (videoMarkers.any { p.contains(it) }) return AppCategory.VIDEO

        // 5. Jogos
        @Suppress("DEPRECATION")
        val isFlagGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        val gameMarkers = listOf(
            "roblox", "supercell", "activision", "mihoyo", "cognosphere", "mojang",
            "minecraft", "riotgames", "com.king.", "com.ea.gp", "gameloft", "zynga",
            "playrix", "nianticlabs", "dts.freefire", "tencent.ig", "epicgames", "chess"
        )
        if (isFlagGame || appInfo.category == ApplicationInfo.CATEGORY_GAME || gameMarkers.any { p.contains(it) }) {
            return AppCategory.GAMES
        }

        // 6. Navegadores, Leitura & Notícias
        val browserNewsMarkers = listOf(
            "chrome", "firefox", "mozilla", "opera", "brave", "emmx", "duckduckgo",
            "vivaldi", "kiwi", "sbrowser", "globalbrowser", "magazines", "flipboard",
            "medium.reader", "substack", "kindle", "wattpad", "noticias", "news"
        )
        if (browserNewsMarkers.any { p.contains(it) }) return AppCategory.NEWS_BROWSER

        // 7. Categorias nativas do Android 8.0+ (Play Store Metadata)
        return when (appInfo.category) {
            ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
            ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMES
            ApplicationInfo.CATEGORY_AUDIO -> AppCategory.MUSIC
            ApplicationInfo.CATEGORY_NEWS -> AppCategory.NEWS_BROWSER
            else -> AppCategory.PRODUCTIVITY
        }
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalMinutes = ms / 60_000L
        if (totalMinutes == 0L) return "< 1m"
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return if (hours > 0L) {
            if (mins > 0L) "${hours}h ${mins}m" else "${hours}h"
        } else {
            "${mins}m"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.1f GB", mb / 1024.0)
        } else if (mb >= 10.0) {
            String.format(Locale.US, "%.0f MB", mb)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
