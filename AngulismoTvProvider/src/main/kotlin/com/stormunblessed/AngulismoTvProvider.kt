package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * angulismotv.pages.dev — agenda deportiva + canales 24/7.
 *
 * The Astro site has no backend: /scripts/inicio/main.js loads a single json with
 * `{ events, channels }` from the author's repo and resolves every card as
 * `/transmision/?c=<canal>&o=<opción>`, which is just `channels[c].options[o].iframe`.
 * This provider reads the same json and hands the iframes to [AngulismoResolver].
 */
class AngulismoTvProvider : MainAPI() {
    companion object {
        private const val DATA_URL =
            "https://raw.githubusercontent.com/tadeoallende/repos-web/refs/heads/main/datos.json"
        private const val DEFAULT_POSTER = "https://angulismotv.pages.dev/assets/logo2.png"
        private const val CACHE_TTL = 60_000L
        private const val LIVE_WINDOW = 2 * 60 * 60 * 1000L
        private const val FEED_TIMEOUT = 30_000L

        private var cachedItems: List<AngulismoItem> = emptyList()
        private var cachedAt = 0L
    }

    override var mainUrl = "https://angulismotv.pages.dev"
    override var name = "AngulismoTV"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Live,
    )

    override val mainPage = mainPageOf(
        "live" to "En Vivo",
        "agenda" to "Agenda",
        "channels" to "Canales",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = loadItems()
        val now = System.currentTimeMillis()
        val events = items.filter { it.kind == KIND_EVENT }
        val list = when (request.data) {
            "live" -> {
                val live = events.filter { it.isLiveAt(now) }.sortedBy { it.startMs }
                if (live.isNotEmpty()) live
                // Outside of match hours the row would be empty; show what is coming next.
                else events.filter { it.startMs != null && it.startMs > now }
                    .sortedBy { it.startMs }
                    .take(6)
            }

            "channels" -> items.filter { it.kind == KIND_CHANNEL }
                .sortedWith(compareByDescending<AngulismoItem> { it.featured }.thenBy { it.title.lowercase() })

            else -> events.filter { !it.isPastAt(now) }.sortedBy { it.startMs ?: Long.MAX_VALUE }
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list.map { it.toSearchResult() },
                isHorizontalImages = true,
            ),
            hasNext = false,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return loadItems()
            .filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.subtitle?.contains(query, ignoreCase = true) == true
            }
            .map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = AppUtils.tryParseJson<AngulismoItem>(url) ?: return null
        return newLiveStreamLoadResponse(item.title, url, url) {
            this.posterUrl = item.poster
            this.plot = buildPlot(item)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val item = AppUtils.tryParseJson<AngulismoItem>(data) ?: return false
        if (item.feeds.isEmpty()) return false
        val resolver = AngulismoResolver(app, subtitleCallback)
        item.feeds.amap { feed ->
            // One blackholed host must never keep the whole source list on the loading screen.
            runCatching { withTimeoutOrNull(FEED_TIMEOUT) { resolver.resolve(feed, callback) } }
            Unit
        }
        return true
    }

    // ------------------------------------------------------------------ data loading

    private suspend fun loadItems(): List<AngulismoItem> {
        val now = System.currentTimeMillis()
        if (cachedItems.isNotEmpty() && now - cachedAt < CACHE_TTL) return cachedItems
        val data = runCatching {
            AppUtils.tryParseJson<AngulismoData>(app.get(DATA_URL, timeout = 25).text)
        }.getOrNull() ?: return cachedItems
        val items = buildItems(data)
        if (items.isNotEmpty()) {
            cachedItems = items
            cachedAt = now
        }
        return cachedItems
    }

    private fun buildItems(data: AngulismoData): List<AngulismoItem> {
        val channels = data.channels.orEmpty()
        val channelsByName = channels
            .filter { !it.name.isNullOrBlank() }
            .associateBy { it.name!!.trim().lowercase() }

        val eventItems = data.events.orEmpty().mapNotNull { event ->
            val title = event.evento?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val feeds = eventFeeds(event, channelsByName)
            if (feeds.isEmpty()) return@mapNotNull null
            val start = parseArtDate(event.fecha)
            AngulismoItem(
                kind = KIND_EVENT,
                title = title,
                subtitle = event.competencia?.trim()?.takeIf { it.isNotEmpty() },
                poster = event.logoUrl ?: event.team1Logo ?: event.team2Logo ?: DEFAULT_POSTER,
                hour = start?.let { formatHour(it) },
                startMs = start?.time,
                feeds = feeds,
            )
        }

        // The site merges repeated entries of the same match (groupDuplicateEvents in main.js)
        // so one card lists every channel of all its sources.
        val mergedEvents = LinkedHashMap<String, AngulismoItem>()
        eventItems.forEach { item ->
            val key = item.title.lowercase().trim()
            val existing = mergedEvents[key]
            mergedEvents[key] = if (existing == null) {
                item
            } else {
                existing.copy(
                    subtitle = existing.subtitle ?: item.subtitle,
                    hour = existing.hour ?: item.hour,
                    startMs = existing.startMs ?: item.startMs,
                    feeds = (existing.feeds + item.feeds)
                        .distinctBy { "${it.channel}|${it.option}|${it.url}" },
                )
            }
        }
        val events = mergedEvents.values.toList()

        val channelItems = channels
            // The site only renders channels flagged with show: true (26 of 42 today).
            .filter { it.show == true && !it.name.isNullOrBlank() }
            .mapNotNull { channel ->
                val feeds = channelFeeds(channel.name, channel.options, channel.url)
                if (feeds.isEmpty()) return@mapNotNull null
                AngulismoItem(
                    kind = KIND_CHANNEL,
                    title = channel.name!!.trim(),
                    poster = channel.logo?.takeIf { it.isNotBlank() } ?: DEFAULT_POSTER,
                    feeds = feeds,
                    featured = channel.featured == true,
                )
            }

        return events + channelItems
    }

    /** Event channels are either global channel names or inline `{ name, options[], url }`. */
    private fun eventFeeds(
        event: AngulismoEvent,
        channelsByName: Map<String, AngulismoChannel>,
    ): List<AngulismoFeed> {
        val feeds = LinkedHashMap<String, AngulismoFeed>()
        event.canales.orEmpty().forEach { raw ->
            val resolved = (when (raw) {
                is String -> channelsByName[raw.trim().lowercase()]
                    ?.let { channelFeeds(it.name, it.options, it.url) }

                is Map<*, *> -> {
                    val inline = inlineFeeds(raw)
                    if (inline.isEmpty()) {
                        val name = (raw["name"] as? String)?.trim()?.lowercase()
                        if (name.isNullOrEmpty()) emptyList()
                        else channelsByName[name]?.let { channelFeeds(it.name, it.options, it.url) }
                            .orEmpty()
                    } else {
                        inline
                    }
                }

                else -> emptyList()
            }).orEmpty()
            resolved.forEach { feed ->
                feeds["${feed.channel}|${feed.option}|${feed.url}"] = feed
            }
        }
        return feeds.values.toList()
    }

    private fun inlineFeeds(raw: Map<*, *>): List<AngulismoFeed> {
        val name = (raw["name"] as? String)?.trim()
        val url = (raw["url"] as? String)?.trim()
        val options = (raw["options"] as? List<*>).orEmpty().mapNotNull { option ->
            (option as? Map<*, *>)?.let {
                AngulismoOption(name = it["name"] as? String, iframe = it["iframe"] as? String)
            }
        }
        return channelFeeds(name, options, url)
    }

    private fun channelFeeds(
        channel: String?,
        options: List<AngulismoOption>?,
        url: String?,
    ): List<AngulismoFeed> {
        val label = channel?.trim()?.takeIf { it.isNotEmpty() } ?: "Directo"
        if (options.isNullOrEmpty()) {
            val direct = url?.trim()
            return if (direct.isNullOrEmpty()) emptyList()
            else listOf(AngulismoFeed(label, null, direct))
        }
        return options.mapNotNull { option ->
            val iframe = option.iframe?.trim()
            if (iframe.isNullOrEmpty()) null
            else AngulismoFeed(label, option.name?.trim()?.takeIf { it.isNotEmpty() }, iframe)
        }
    }

    // ----------------------------------------------------------------------- helpers

    private fun AngulismoItem.toSearchResult(): SearchResponse {
        val label = if (hour != null) "$hour $title" else title
        return newLiveSearchResponse(label, toData(), TvType.Live) {
            this.posterUrl = this@toSearchResult.poster
        }
    }

    private fun buildPlot(item: AngulismoItem): String {
        val rows = mutableListOf<String>()
        item.subtitle?.let { rows += it }
        item.hour?.let { rows += "Hora: $it (${dayLabel(item.startMs)})" }
        val channels = item.feeds.map { it.channel }.distinct()
        if (channels.isNotEmpty()) {
            rows += if (item.kind == KIND_CHANNEL) "Opciones: ${item.feeds.size}"
            else "Canales: ${channels.joinToString(", ")}"
        }
        return rows.joinToString("\n")
    }

    /** Timestamps in datos.json are Argentina time (UTC-3), see parseARTDate() in main.js. */
    private fun parseArtDate(raw: String?): java.util.Date? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT-3")
            }.parse(raw.trim())
        }.getOrNull()
    }

    private fun formatHour(date: java.util.Date): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(date)

    private fun dayLabel(startMs: Long?): String {
        if (startMs == null) return "hoy"
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val now = Calendar.getInstance()
        val today = day.format(now.time)
        val target = day.format(java.util.Date(startMs))
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        return when (target) {
            today -> "hoy"
            day.format(tomorrow.time) -> "mañana"
            else -> SimpleDateFormat("dd/MM", Locale.US).format(java.util.Date(startMs))
        }
    }

    private fun AngulismoItem.isLiveAt(now: Long): Boolean {
        val start = startMs ?: return false
        return now >= start && now <= start + LIVE_WINDOW
    }

    private fun AngulismoItem.isPastAt(now: Long): Boolean {
        val start = startMs ?: return false
        return now > start + LIVE_WINDOW
    }
}

private const val KIND_EVENT = "event"
private const val KIND_CHANNEL = "channel"
