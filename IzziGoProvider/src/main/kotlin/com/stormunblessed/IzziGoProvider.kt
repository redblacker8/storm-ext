package com.stormunblessed

import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID

data class IzziRef(
    val cid: String,
    val type: String,
    val url: String? = null,
    val packaging: String? = null,
    val drm: String? = null,
    val title: String? = null,
    val poster: String? = null,
)

class IzziGoProvider(private val api: IzziGoApi) : MainAPI() {

    override var mainUrl = "https://www.izzigo.tv"
    override var name = "izzi go"
    override var lang = "mx"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = false
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val mapper = ObjectMapper()

    companion object {
        private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        private const val PAGE_SIZE = 40
        private const val VIEW = "stb_contents_list_view"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0"
    }

    private val liveCategories = mapOf(
        "sports" to "Sports",
        "news" to "News",
        "kids" to "Children",
        "movies" to "Movies",
        "series" to "Series",
        "music" to "Music",
        "culture" to "Culture",
        "entertainment" to "Entertainment",
    )

    // Fallback for channels that have no EPG genre data (e.g. Fox+).
    private val categoryKeywords = mapOf(
        "sports" to listOf(
            "espn", "fox sports", "fox+", "tudn", "sky sport", "nba", "nfl", "mlb", "nhl",
            "tennis", "golf", "adrenalina", "hi sports", "real madrid", "ufc", "wwe", "bein",
            "claro sports", "tyc", "dsports", "directv sports", "win sports", "laliga",
            "la liga", "formula 1", "f1", "motogp", "nascar", "eurosport", "dazn", "gol tv",
            "fox deportes",
        ),
        "news" to listOf(
            "cnn", "fox news", "milenio", "bloomberg", "euronews", "noticias", "telemundo",
            "foro tv", "adn40", "mvs", "financiero", "bbc", "ntn24", "24 horas", "telediario",
            "azteca noticias", "once noticias", "imagen noticias", "dw tv",
        ),
        "kids" to listOf(
            "disney", "cartoon", "nick", "discovery kid", "baby", "cbeebies", "boomerang",
            "semillitas", "tooncast", "pbs kids",
        ),
        "movies" to listOf(
            "hbo", "cinemax", "star channel", "golden", "space", "tnt", "amc", "fx", "sony",
            "universal cinema", "de pelicula", "de película", "cinecanal", "multipremier",
            "multicinema", "cinelatino", "cinema",
        ),
        "series" to listOf(
            "a&e", "warner", "universal", "tnt series", "axn", "star series",
        ),
        "music" to listOf("mtv", "telehit", "bandamax", "vh1", "htv", "muchmusic", "qello"),
        "culture" to listOf(
            "discovery channel", "discovery h&h", "discovery science", "discovery theater",
            "discovery world", "discovery turbo", "nat geo", "national geographic", "history",
            "animal planet", "h2", "investigation", "science", "civilization", "dmax",
            "hgtv", "travel", "documental",
        ),
        "entertainment" to listOf(
            "usa network", "tbs", "tru tv", "distrito comedia", "univision", "las estrellas",
            "canal 5", "image", "comedy central",
        ),
    )

    private fun matchesKeywords(channel: JsonNode, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val name = channel["loc"]?.firstOrNull()?.get("nam")?.asText()?.lowercase() ?: return false
        return keywords.any { name.contains(it) }
    }

    override val mainPage: List<MainPageData> = mainPageOf(
        "recent" to "Recientes",
        "live" to "TV en vivo",
        "sports" to "Deportes",
        "news" to "Noticias",
        "kids" to "Infantil",
        "movies" to "Cine",
        "series" to "Series",
        "music" to "Música",
        "culture" to "Cultura",
        "entertainment" to "Entretenimiento",
        "radio" to "Radio",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "recent") {
            val items = api.recentChannels().map { refToSearch(it) }
            return newHomePageResponse(listOf(HomePageList("Recientes", items, true)), false)
        }

        api.ensureLogin()
        val channels = api.channels()

        if (request.data == "live") {
            val tv = channels.filter { it["sty"]?.asText() == "TV_CHANNEL" }
            return newHomePageResponse(
                listOf(HomePageList("Canales", tv.mapNotNull { channelToSearch(it) }, true)),
                false,
            )
        }

        if (request.data == "radio") {
            val radio = channels.filter { it["sty"]?.asText() == "RADIO_CHANNEL" }
            return newHomePageResponse(
                listOf(HomePageList("Radio", radio.mapNotNull { channelToSearch(it) }, true)),
                false,
            )
        }

        val categoryName = liveCategories[request.data]
            ?: return newHomePageResponse(listOf(HomePageList(request.name, emptyList(), true)), false)
        val genres = api.categoryGenres(categoryName)
        if (genres.isNullOrBlank()) {
            return newHomePageResponse(listOf(HomePageList(request.name, emptyList(), true)), false)
        }
        val ids = api.channelIdsByGenre(genres).toHashSet()
        val keywords = categoryKeywords[request.data].orEmpty()
        val items = channels
            .filter { it["sty"]?.asText() == "TV_CHANNEL" }
            .filter { it["sid"]?.asText() in ids || matchesKeywords(it, keywords) }
            .mapNotNull { channelToSearch(it) }
        return newHomePageResponse(listOf(HomePageList(request.name, items, true)), false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        api.ensureLogin()
        val q = query.trim()
        if (q.isBlank()) return null
        val items = api.channels()
            .filter {
                val sty = it["sty"]?.asText()
                sty == "TV_CHANNEL" || sty == "RADIO_CHANNEL"
            }
            .filter {
                it["loc"]?.firstOrNull()?.get("nam")?.asText()?.contains(q, ignoreCase = true) == true
            }
            .mapNotNull { channelToSearch(it) }
        if (items.isEmpty()) return null
        return items.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = tryParseJson<IzziRef>(url) ?: return null
        api.ensureLogin()

        if (ref.type == "CHANNEL") {
            return newLiveStreamLoadResponse(ref.title ?: "Canal", url, url) {
                this.posterUrl = ref.poster
            }
        }

        val content = api.content(ref.cid) ?: return null
        val loc = content["loc"]?.firstOrNull()
        val title = loc?.get("tit")?.asText() ?: ref.title ?: return null
        val plot = loc?.get("syn")?.asText() ?: ""
        val poster = imgUrl(loc, "pos", "l") ?: imgUrl(loc, "sna", "l")
        val background = imgUrl(loc, "bac", "l")

        if (ref.type == "SERIES") {
            val episodes = mutableListOf<Episode>()
            for (season in api.seasons(ref.cid)) {
                val seasonId = season["cid"]?.asText() ?: continue
                val seasonNumber = season["sea"]?.asInt()
                for (ep in api.episodes(seasonId)) {
                    val epCid = ep["cid"]?.asText() ?: continue
                    val delivery = contentDelivery(ep)
                    val epRef = IzziRef(
                        cid = epCid,
                        type = "EPISODE",
                        url = delivery?.first,
                        packaging = delivery?.second,
                        drm = delivery?.third,
                    ).toJson()
                    val epNumber = ep["scn"]?.asInt()
                    episodes.add(
                        newEpisode(epRef) {
                            this.name = "Episodio $epNumber"
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = imgUrl(ep["loc"]?.firstOrNull(), "sna", "l") ?: poster
                            this.runTime = ep["dur"]?.asInt() ?: 0
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = tryParseJson<IzziRef>(data) ?: return false
        return try {
            api.ensureLogin()

            var streamUrl = ref.url
            var packaging = ref.packaging ?: "DASH"
            var drm = ref.drm ?: "WV"

            if (streamUrl.isNullOrBlank()) {
                val content = api.content(ref.cid) ?: return false
                val delivery = contentDelivery(content) ?: return false
                streamUrl = delivery.first
                packaging = delivery.second
                drm = delivery.third
            }

            if (drm == "CLEAR") {
                val stream = api.playableUrl(streamUrl, packaging, "CLEAR") ?: return false
                val playUrl = api.nodeCorrectedStream(stream)?.also {
                    Log.d("IzziGo", "CLEAR node fix $stream -> $it")
                } ?: stream
                recordRecent(ref)
                callback.invoke(
                    newExtractorLink(name, name, playUrl) {
                        this.quality = Qualities.Unknown.value
                        this.type = if (packaging == "HLS") ExtractorLinkType.M3U8 else ExtractorLinkType.DASH
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                return true
            }

            api.ensureProvisioned()
            val stream = api.playableUrl(streamUrl, packaging, drm)
            if (stream.isNullOrBlank()) {
                Log.e("IzziGo", "playableUrl returned null for $streamUrl ($packaging/$drm)")
                return false
            }
            val playUrl = api.nodeCorrectedStream(stream)?.also {
                Log.d("IzziGo", "node fix $stream -> $it")
            } ?: stream
            val license = api.licenseUrl(streamUrl, packaging, drm)
            if (license.isNullOrBlank()) {
                Log.e("IzziGo", "licenseUrl returned null for $streamUrl ($packaging/$drm)")
                return false
            }
            Log.d("IzziGo", "emitting DRM link stream=$playUrl licenseHost=${license.substringBefore('?')}")

            recordRecent(ref)
            callback.invoke(
                newDrmExtractorLink(name, name, playUrl, ExtractorLinkType.DASH, WIDEVINE_UUID) {
                    this.licenseUrl = license
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                    )
                }
            )
            true
        } catch (e: Exception) {
            Log.e("IzziGo", "loadLinks failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun JsonNode?.nodeList(): List<JsonNode> {
        if (this == null || !isArray) return emptyList()
        return toList()
    }

    private fun imgUrl(loc: JsonNode?, key: String, size: String): String? {
        if (loc == null) return null
        val link = loc["img_lnk"]?.get(key) ?: return null
        val url = link["url"]?.asText() ?: return null
        val sizeValue = link["sizes"]?.get(size)?.asText() ?: return null
        return mainUrl + url.replace("{image-size}", sizeValue)
    }

    private fun combToDrm(comb: JsonNode?): Pair<String, String>? {
        if (comb == null) return null
        val packaging = comb["packg"]?.asText() ?: return null
        val drms = comb["drm"]?.map { it.asText() } ?: emptyList()
        val drm = when {
            drms.contains("WV") -> "WV"
            drms.contains("PR") -> "PR"
            else -> "CLEAR"
        }
        return packaging to drm
    }

    private fun channelDelivery(channel: JsonNode): Triple<String, String, String>? {
        val liv = channel["liv"]?.firstOrNull() ?: return null
        val url = liv["url"]?.asText() ?: return null
        val comb = liv["comb"]?.firstOrNull { it["packg"]?.asText() == "DASH" }
            ?: liv["comb"]?.firstOrNull()
        val (packaging, drm) = combToDrm(comb) ?: return null
        return Triple(url, packaging, drm)
    }

    private fun contentDelivery(item: JsonNode): Triple<String, String, String>? {
        val delivery = item["del"]?.firstOrNull() ?: return null
        val urls = delivery["urls"] ?: return null
        val entry = urls.firstOrNull { it["url"]?.asText()?.startsWith("multirights") == true }
            ?: urls.firstOrNull() ?: return null
        val url = entry["url"]?.asText() ?: return null
        val comb = entry["comb"]?.firstOrNull { it["packg"]?.asText() == "DASH" }
            ?: entry["comb"]?.firstOrNull()
        val (packaging, drm) = combToDrm(comb) ?: return null
        return Triple(url, packaging, drm)
    }

    private fun channelToSearch(channel: JsonNode): SearchResponse? {
        val sid = channel["sid"]?.asText() ?: return null
        val loc = channel["loc"]?.firstOrNull() ?: return null
        val title = loc["nam"]?.asText() ?: return null
        val logo = imgUrl(loc, "log", "m") ?: imgUrl(loc, "log", "l")
        val delivery = channelDelivery(channel)
        val ref = IzziRef(
            cid = sid,
            type = "CHANNEL",
            url = delivery?.first,
            packaging = delivery?.second,
            drm = delivery?.third,
            title = title,
            poster = logo,
        ).toJson()
        return newLiveSearchResponse(title, ref, TvType.Live) {
            this.posterUrl = logo
        }
    }

    private fun refToSearch(ref: IzziRef): SearchResponse =
        newLiveSearchResponse(ref.title ?: "Canal", ref.toJson(), TvType.Live) {
            this.posterUrl = ref.poster
        }

    /** Persists the channel in the recents list and refreshes the cached home page. */
    private fun recordRecent(ref: IzziRef) {
        if (ref.type != "CHANNEL") return
        if (api.addRecentChannel(ref)) {
            MainActivity.reloadHomeEvent.invoke(true)
        }
    }
}
