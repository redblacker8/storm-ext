package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.stormunblessed.FTVHDApiResponse
import com.stormunblessed.StreamedInfo
import org.mozilla.javascript.Context
import java.net.URL
import java.util.Calendar

data class La14HDMatchInfo(
    val category: String,
    val link: String,
    val title: String,
    val time: String,
    val status: String,
    val language: String?,
    val date: String?
)

enum class SiteKey {
    RUSTICO,
    FUTBOLLIBRE,
    TVTVHD,
    LA14HD,
    STREAMTP,
    STREAMXX,
    CANALESDEPORTIVOS,
    ANGULISMO,
}

data class PartidoJson(
    val hora_utc: String,
    val logo: String,
    val liga: String,
    val equipos: String,
    val canales: List<CanalInfo>
)

data class CanalInfo(
    val nombre: String,
    val url: String,
    val calidad: String
)

data class StreamTPEvent(
    val id: Int?,
    val category: String?,
    val title: String?,
    val time: String?,
    val flags: List<String>?,
    val links: List<StreamTPLink>?
)

data class StreamTPLink(
    val lang: StreamTPLang?,
    val quality: StreamTPQuality?,
    val server: String?,
    val bitrate: String?,
    val url: String?,
    val status: String?
)

data class StreamTPLang(
    val code: String?,
    val label: String?
)

data class StreamTPQuality(
    val type: String?,
    val label: String?
)

data class StreamTPResponse(
    val events: List<StreamTPEvent>?
)

data class Site(
    val key: SiteKey,
    val mainUrl: String,
    val agendaUrl: String,
)

data class AngulismoResponse(
    val events: List<AngulismoEvent>?
)

data class AngulismoEvent(
    val id: Int?,
    val evento: String?,
    val fecha: String?,
    val competencia: String?,
    val logoUrl: String?,
    val canales: List<AngulismoCanal>?
)

data class AngulismoCanal(
    val name: String?,
    val options: List<AngulismoOption>?
)

data class AngulismoOption(
    val name: String?,
    val iframe: String?
)

class DeporTVProvider : MainAPI() {
    companion object {
        private var cachedEvents: List<EventData> = emptyList()
        private var cacheTimestamp: Long = 0L
        private const val CACHE_TTL = 60_000L // 1 minute
    }

    override var mainUrl = ""

    val sites: List<Site> =
        listOf(
            Site(
                SiteKey.RUSTICO,
                "https://rusticotv.su",
                "/agenda.php"
            ),
            Site(
                SiteKey.FUTBOLLIBRE,
                "https://futbollibres.net.pe/",
                "/agenda/"
            ),
            Site(
                SiteKey.TVTVHD,
                "https://tvhd2.com",
                "https://pltvhd.com/diaries.json"
            ),
            Site(
                SiteKey.STREAMXX,
                "https://streamx996.one",
                "/json/agenda550.json?nocache=${Date().time}",
            ),
            Site(
                SiteKey.ANGULISMO,
                "https://angulismotv.pages.dev",
                "https://raw.githubusercontent.com/Aguus467/test/refs/heads/main/json.json",
            ),
            // Site(
            //     SiteKey.STREAMTP,
            //     "https://streamtpday1.xyz",
            //     "/wc.json?_=${Date().time}"
            // ),
            // Site(
            //     SiteKey.CANALESDEPORTIVOS,
            //     "https://canalesdeportivos.net",
            //     "https://canalesdeportivos.net/partidos.json?v=${Date().time}"
            // ),
            // Site(
            //     SiteKey.LA14HD,
            //     "https://la14hd.com",
            //     "/eventos/json/agenda123.json"
            // ),
        )
    override var name = "DeporTV"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Live
    )
    val streamedInfo: StreamedInfo = StreamedInfo()
    val defaultPoster = "https://new.tvpublica.com.ar/wp-content/uploads/2021/05/DeporTVOK.jpg"

    override val mainPage = mainPageOf(
        "es/agenda/" to "Agenda",
    )

    suspend fun followRedirects(url: String): String {
        val jsRedirectRegex = Regex("""window\.location\.href\s*=\s*"([^"]+)";""")
        val res = app.get(url, timeout = 5, allowRedirects = false)
        val data = res.document.data()
        val jsRedirectUrl = jsRedirectRegex.find(data)?.groupValues?.get(1)
        if (jsRedirectUrl != null) {
            return jsRedirectUrl
        }
        val metaRedirectUrl =
            res.document.selectFirst("head meta[http-equiv=refresh]")?.attr("content")
                ?.substringAfter("url=")
        if (metaRedirectUrl != null) {
            return metaRedirectUrl
        }
        try {
            return app.get(url, timeout = 5, allowRedirects = true).url
        } catch (e: Exception) {
            return url;
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        streamedInfo.init()
        val agendaData = sites.amap {
            var url = ""
            if (it.agendaUrl.startsWith("http")) {
                url = it.agendaUrl
            } else {
                val mainUrl = followRedirects(it.mainUrl)
                url = mainUrl + it.agendaUrl
            }
            var res: NiceResponse? = null;
            try {
                res = app.get(url, timeout = 5, referer = it.mainUrl)
            } catch (e: Exception) {
            }
            var events: List<EventData> = emptyList()
            if (res != null) {
                if (it.key.equals(SiteKey.STREAMTP)) {
                    events = AppUtils.tryParseJson<StreamTPResponse>(res.text)?.events
                        ?.flatMap { event ->
                            val title = event.title ?: return@flatMap emptyList()
                            val time = event.time ?: "00:00"
                            val matchId = streamedInfo.searchPosterByTitle(title)
                            val urls = event.links?.mapNotNull { link -> link.url } ?: emptyList()
                            listOf(
                                EventData(
                                    matchId.title,
                                    matchId.hour ?: transformHourToLocal(time, "GMT-5"),
                                    urls,
                                    matchId.poster
                                )
                            )
                        } ?: emptyList()
                } else if (it.key.equals(SiteKey.LA14HD)
                    || it.key.equals(SiteKey.STREAMXX)
                ) {
                    events = AppUtils.tryParseJson<List<La14HDMatchInfo>>(res.text)
                        ?.map {
                            val matchId = streamedInfo.searchPosterByTitle(it.title)
                            EventData(
                                matchId.title,
                                matchId.hour ?: transformHourToLocal(it.time, "GMT-5"),
                                listOf(it.link),
                                matchId.poster
                            )
                        } ?: emptyList()
                } else if (it.key.equals(SiteKey.TVTVHD)) {
                    val siteUrl = it.mainUrl
                    events = AppUtils.tryParseJson<FTVHDApiResponse>(res.text)?.data
                        ?.map {
                            val matchId =
                                streamedInfo.searchPosterByTitle(it.attributes.diaryDescription)
                            EventData(
                                matchId.title,
                                matchId.hour ?: transformHourToLocal(
                                    it.attributes.diaryHour.substringBeforeLast(":"),
                                    "GMT-5"
                                ),
                                it.attributes.embeds.data.map { embed ->
                                    val url = embed.attributes.embedIframe
                                    if (url.startsWith("http")) url else "$siteUrl$url"
                                },
                                matchId.poster
                            )
                        } ?: emptyList()
                } else if (it.key.equals(SiteKey.CANALESDEPORTIVOS)) {
                    events = AppUtils.tryParseJson<List<PartidoJson>>(res.text)
                        ?.map { partido ->
                            val matchId = streamedInfo.searchPosterByTitle(partido.equipos)
                            val hour = transformUtcToLocal(partido.hora_utc)
                            EventData(
                                matchId.title,
                                matchId.hour ?: hour,
                                partido.canales.map { canal ->
                                    if (canal.url.startsWith("http")) canal.url else "${it.mainUrl}${canal.url}"
                                },
                                matchId.poster
                            )
                        } ?: emptyList()
                } else if (it.key.equals(SiteKey.ANGULISMO)) {
                    events = AppUtils.tryParseJson<AngulismoResponse>(res.text)?.events
                        ?.map { evento ->
                            val matchId = streamedInfo.searchPosterByTitle(evento.evento ?: "")
                            val urls = evento.canales?.flatMap { canal ->
                                canal.options?.mapNotNull { option -> option.iframe } ?: emptyList()
                            } ?: emptyList()
                            val time = evento.fecha?.substringAfter(" ")?.substringBeforeLast(":") ?: "00:00"
                            EventData(
                                matchId.title,
                                matchId.hour ?: transformHourToLocal(time, "GMT-6"),
                                urls,
                                matchId.poster ?: evento.logoUrl
                            )
                        } ?: emptyList()
                } else {
                    events = res.document.select(".menu > li")
                        .mapNotNull { it.rusticoToEventData(url) }
                }
            }
            events
        }.flatten()

        cachedEvents = agendaData
            .groupBy { it.title.substringAfter(":").trim() }
            .amap { (title, events) ->
                val posterUrl = events.first().poster ?: defaultPoster
                EventData(
                    title = title,
                    hour = events.first().hour,
                    urls = events.flatMap { it.urls }.distinct(),
                    poster = posterUrl
                )
            }.sortedBy { it.hour.substringBefore(":").toIntOrNull() }
        cacheTimestamp = Date().time

        val live = cachedEvents.filter { isEventLive(it.hour) }
        return newHomePageResponse(
            list = listOf(
                HomePageList("En Vivo", live.map { it.toSearchResult() }, isHorizontalImages = true),
                HomePageList(request.name, cachedEvents.map { it.toSearchResult() }, isHorizontalImages = true),
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return cachedEvents
            .filter { it.title.contains(query, ignoreCase = true) }
            .map { it.toSearchResult() }
    }

    private fun Element.rusticoToEventData(mainUrl: String): EventData? {
        val titleElement = this.selectFirst("a")
        val matchTitle = titleElement?.ownText() ?: ""
        if (matchTitle.startsWith("Zapping Sports"))
            return null
        val hour = titleElement?.selectFirst("span")?.text() ?: "00:00"
        val hourLocal = transformHourToLocal(hour, "GMT+1")
        val urls = this.select("ul li").mapNotNull {
            it.selectFirst("a")?.attr("href")?.replaceFirst("^/".toRegex(), "$mainUrl/")
        }
        val matchId = streamedInfo.searchPosterByTitle(matchTitle)
        return EventData(matchId.title, matchId.hour ?: hourLocal, urls, matchId.poster)
    }

    private fun EventData.toSearchResult(): SearchResponse {
        val title = "${this.hour} ${this.title}"
        val posterUrl = this.poster
        return newLiveSearchResponse(
            title,
            this.toJson(),
            TvType.Live
        ) {
            this.posterUrl = posterUrl
        }
    }

    fun String.replaceLast(oldValue: String, newValue: String): String {
        val lastIndex = this.lastIndexOf(oldValue)
        return if (lastIndex == -1) {
            this // nothing to replace
        } else {
            this.substring(0, lastIndex) + newValue + this.substring(lastIndex + oldValue.length)
        }
    }


//    override suspend fun search(query: String): List<SearchResponse> {
//        val document = app.get("${mainUrl}/?s=$query").document
//        val results =
//            document.select("div.container div.card__cover").mapNotNull { it.toSearchResult() }
//        return results
//    }

    override suspend fun load(data: String): LoadResponse? {
        val eventData = AppUtils.tryParseJson<EventData>(data)
        if (eventData == null)
            return null
        return newLiveStreamLoadResponse(eventData.title, data, data) {
            this.posterUrl = eventData.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val eventData = AppUtils.tryParseJson<EventData>(data)
        if (eventData == null)
            return false
        eventData.urls.amap {
            var frame = if (it.contains("?r=")) {
                base64Decode(
                    it.substringAfter("?r=")
                )
                    .replaceFirst(
                        "https://vivolibre.org/global1.php?stream=",
                        "https://streamtpday1.xyz/global1.php?stream="
                    ).replaceFirst(
                        "https://domainmy.lat/global1.php?stream=",
                        "https://streamtpday1.xyz/global1.php?stream="
                    )
                    .replaceFirst(
                        "https://librefutbolhd.su/embed/canales.php?stream=",
                        "https://tvtvhd.com/vivo/canales.php?stream="
                    ).replaceFirst(
                        "https://latamx701.org/global2.php?stream=",
                        "https://la18hd.com/vivo/canales.php?stream="
                    ).replaceFirst(
                        "https://latamx701.org/global1.php?stream=",
                        "https://streamtp-x-y-z.ws/global1.php?stream="
                    )
            } else it
            if (frame.contains("canales.php?stream=") || frame.contains("canal.php?stream=")) {
                val source = URL(frame).host
                val name = frame.substringAfter("?stream=")
                val iframeSrc = app.get(frame, referer = it).document.selectFirst("iframe")?.attr("src")
                val url = if (iframeSrc != null && iframeSrc.startsWith("http")) {
                    iframeSrc
                } else if (iframeSrc != null) {
                    "https://${URL(frame).host}$iframeSrc"
                } else {
                    frame
                }
                val doc = app.get(url, referer = frame).document
                val link =
                    doc.select("script").firstOrNull { it.data().contains("var playbackURL = ") }
                        ?.data()
                        ?.substringAfter("var playbackURL = \"")?.substringBefore("\";")
                if (link != null)
                    callback(
                        newExtractorLink(
                            "${source}[$name]",
                            "${source}[$name]",
                            link,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
            } else if (frame.contains("global1.php?")) {
                val source = URL(frame).host
                val chanelNameParameter = frame.substringAfter(".php?").substringBefore("=")
                val name = frame.substringAfter(".php?$chanelNameParameter=")
                val doc = app.get(frame, headers = mapOf("Sec-Fetch-Dest" to "iframe")).document
                var result =
                    doc.select("script").firstOrNull { it.html().contains("var playbackURL") }
                        ?.let {
                            var result = ""
                            val scriptContent = it.data().substringBefore("var p2pConfig")
                            val rhino = Context.enter()
                            rhino.setInterpretedMode(true)
                            val scope = rhino.initStandardObjects()
                            try {
                                scope.put(
                                    "atob",
                                    scope,
                                    object : org.mozilla.javascript.BaseFunction() {
                                        override fun call(
                                            cx: org.mozilla.javascript.Context,
                                            scope: org.mozilla.javascript.Scriptable,
                                            thisObj: org.mozilla.javascript.Scriptable,
                                            args: Array<out Any>
                                        ): Any {
                                            val str = args[0] as String
                                            val decoded =
                                                android.util.Base64.decode(str, Base64.DEFAULT)
                                            return String(decoded, Charsets.UTF_8)
                                        }
                                    })
                                rhino.evaluateString(scope, scriptContent, "playbackURL", 1, null)
                                result = scope.get("playbackURL", scope).toString()
                            } catch (e: Exception) {
                            } finally {
                                rhino.close()
                            }
                            result
                        }
                if (!result.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(
                            "${source}[$name]",
                            "${source}[$name]",
                            result,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else if (frame.contains("global2.php?")) {
                val source = URL(frame).host
                val chanelNameParameter = frame.substringAfter(".php?").substringBefore("=")
                val name = frame.substringAfter(".php?$chanelNameParameter=")
                val doc = app.get(frame, headers = mapOf("Sec-Fetch-Dest" to "iframe")).document
                val link = doc.select("script").firstOrNull { it.data().contains("var playbackURL") }
                    ?.data()
                    ?.let { script ->
                        val between = script.substringBefore("var p2pConfig")
                        between.substringAfter("var playbackURL = \"").substringBefore("\";")
                            .replace("\\/", "/").takeIf { s -> s.isNotBlank() }
                    }
                if (!link.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(
                            "${source}[$name]",
                            "${source}[$name]",
                            link,
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = frame
                            this.headers = mapOf(
                                "Origin" to "https://${URL(frame).host}",
                                "Referer" to frame
                            )
                        }
                    )
                }
            } else if (frame.startsWith("https://sudamericaplay2.com")) {
                val source = URL(frame).host
                val name = frame.substringAfterLast("/").substringBefore(".")
                try {
                    val doc = app.get(frame, headers = mapOf("Sec-Fetch-Dest" to "iframe")).document
                    val scripts = doc.select("script").map { it.data() }
                    val rhino = Context.enter()
                    rhino.setInterpretedMode(true)
                    val scope = rhino.initStandardObjects()
                    try {
                        scope.put("atob", scope, object : org.mozilla.javascript.BaseFunction() {
                            override fun call(
                                cx: org.mozilla.javascript.Context,
                                scope: org.mozilla.javascript.Scriptable,
                                thisObj: org.mozilla.javascript.Scriptable,
                                args: Array<out Any>
                            ): Any {
                                val str = args[0] as String
                                val decoded = android.util.Base64.decode(str, Base64.DEFAULT)
                                return String(decoded, Charsets.UTF_8)
                            }
                        })
                        val fakeDoc = rhino.newObject(scope, "Object") as org.mozilla.javascript.Scriptable
                        scope.put("document", scope, fakeDoc)
                        val fakeWindow = rhino.newObject(scope, "Object") as org.mozilla.javascript.Scriptable
                        scope.put("window", scope, fakeWindow)
                        scope.put("navigator", scope, rhino.newObject(scope, "Object"))
                        scope.put("setTimeout", scope, object : org.mozilla.javascript.BaseFunction() {
                            override fun call(
                                cx: org.mozilla.javascript.Context,
                                scope: org.mozilla.javascript.Scriptable,
                                thisObj: org.mozilla.javascript.Scriptable,
                                args: Array<out Any>
                            ): Any {
                                if (args.isNotEmpty() && args[0] is org.mozilla.javascript.BaseFunction) {
                                    try {
                                        (args[0] as org.mozilla.javascript.BaseFunction).call(
                                            cx,
                                            scope,
                                            thisObj,
                                            emptyArray()
                                        )
                                    } catch (_: Exception) {
                                    }
                                }
                                return 0.0
                            }
                        })
                        scope.put("setInterval", scope, scope.get("setTimeout", scope))
                        val cleanScripts = scripts.joinToString("\n") { s ->
                            s.replace(Regex("document\\.domain\\s*=.*?;"), "")
                                .replace(Regex("window\\.location\\.(href|replace)\\s*=.*?;"), "")
                                .replace(Regex("!function\\(\\)\\{try\\{.*?\\}\\}\\(\\);"), "")
                        }
                        rhino.evaluateString(scope, cleanScripts, "sudamericaplay", 1, null)
                        val playbackUrl = scope.get("streamUrl", scope)?.toString()
                            ?: scope.get("url", scope)?.toString()
                            ?: scope.get("playbackURL", scope)?.toString()
                        val ckId = scope.get("ck_id", scope)?.toString()
                        val ckKey = scope.get("ck_key", scope)?.toString()
                        if (!playbackUrl.isNullOrEmpty() && playbackUrl.startsWith("http")) {
                            if (playbackUrl.contains(".mpd") && !ckId.isNullOrEmpty() && !ckKey.isNullOrEmpty()) {
                                val drmKidBytes = ckId.chunked(2)
                                    .map { it.toInt(16).toByte() }
                                    .toByteArray()
                                val drmKidBase64 = Base64.encodeToString(
                                    drmKidBytes,
                                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                )
                                val drmKeyBytes = ckKey.chunked(2)
                                    .map { it.toInt(16).toByte() }
                                    .toByteArray()
                                val drmKeyBase64 = Base64.encodeToString(
                                    drmKeyBytes,
                                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                )
                                callback.invoke(
                                    newDrmExtractorLink(
                                        "${source}[$name]",
                                        "${source}[$name]",
                                        playbackUrl,
                                        ExtractorLinkType.DASH,
                                        CLEARKEY_UUID
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.kid = drmKidBase64
                                        this.key = drmKeyBase64
                                        this.referer = frame
                                        this.headers = mapOf(
                                            "Origin" to "https://${URL(frame).host}",
                                            "Referer" to frame
                                        )
                                    }
                                )
                            } else {
                                callback(
                                    newExtractorLink(
                                        "${source}[$name]",
                                        "${source}[$name]",
                                        playbackUrl,
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.referer = frame
                                        this.headers = mapOf(
                                            "Origin" to "https://${URL(frame).host}",
                                            "Referer" to frame
                                        )
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        rhino.close()
                    }
                } catch (_: Exception) {
                }
            } else if (frame.startsWith("https://rojadirectatve.com")) {
                val url = frame.substringAfter("?get=")
                val source = URL(url).host
                val name = url.substringAfter("/repro/").substringBefore(".html")
                app.get(url).document.selectFirst("iframe")?.attr("src")
                    ?.replaceFirst("//", "https://")?.let {
                        val lastFrameUrl = it
                        app.get(
                            lastFrameUrl,
                            referer = "https://rojadirectatve.com/",
                            headers = mapOf("Sec-Fetch-Dest" to "iframe")
                        ).document.select("script")
                            .firstOrNull {
                                it.data().contains("eval(function(p,a,c,k,e,d)") && it.data()
                                    .contains("Clappr")
                            }?.let {
                                val script = getAndUnpack(it.data())
                                if (script.contains("src=")) {
                                    callback(
                                        newExtractorLink(
                                            "${source}[$name]",
                                            "${source}[$name]",
                                            script.substringAfter("src=\"")
                                                .substringBefore("\";")
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                            this.referer = lastFrameUrl
                                        }
                                    )
                                }
                            }
                    }
            } else if (frame.startsWith("https://stgruber.world")) {
                // https://stgruber.world/cobo1.php?id=UNIVERSO
                val source = URL(frame).host
                var result = app.get(
                    frame,
                    referer = "https://ww.futbollibre-tv.su/"
                ).document.select("script")
                    .first { it.data().contains("ConfiguracionCanales =") }?.data()
                    ?.substringAfter("ConfiguracionCanales = {")?.substringBefore("};")
                result = "{$result}"
                val json = result
                    .replace("url:", "\"url\":")
                    .replace("k1:", "\"k1\":")
                    .replace("k2:", "\"k2\":")
                    .replace(Regex(",\\s*\\}"), "}")
                var channels = AppUtils.tryParseJson<Map<String, StgruberChannelInfo>>(json)
                val channelId = frame.substringAfter("?id=")
                val channelinfo = channels?.get(channelId)
                if (channelinfo != null) {
                    if (channelinfo.url.contains("mpd")) {
                        val drmKidBytes = channelinfo.k1?.chunked(2)
                            ?.map { it.toInt(16).toByte() }
                            ?.toByteArray()
                        val drmKidBase64 = Base64.encodeToString(
                            drmKidBytes,
                            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                        )
                        val drmKeyBytes = channelinfo.k2?.chunked(2)
                            ?.map { it.toInt(16).toByte() }
                            ?.toByteArray()
                        val drmKeyBase64 = Base64.encodeToString(
                            drmKeyBytes,
                            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                        )
                        callback.invoke(
                            newDrmExtractorLink(
                                "${source}[$channelId]",
                                "${source}[$channelId]",
                                channelinfo.url,
                                ExtractorLinkType.DASH,
                                CLEARKEY_UUID
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.kid = drmKidBase64
                                this.key = drmKeyBase64
                            }
                        )
                    } else {
                        callback(
                            newExtractorLink(
                                "${source}[$channelId]",
                                "${source}[$channelId]",
                                channelinfo.url
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } else if (
                frame.startsWith("https://envivo1.org/zk.php?id=")
                || frame.startsWith("https://voodc.com")
            ) {
                if (frame.startsWith("https://envivo1.org/zk.php?id=")) {
                    val resolver = WebViewResolver(
                        interceptUrl = Regex("""voodc\.com/embed"""),
                        additionalUrls = listOf(Regex("""voodc\.com/embed""")),
                        useOkhttp = false,
                        timeout = 3_000L
                    )
                    frame = app.get(frame, interceptor = resolver).url
                }
                if (frame.startsWith("https://voodc.com")) {
                    val subFrameUrl = if (frame.startsWith("https://voodc.com/embed")) {
                        app.get(frame).document.select("script")
                            .first { it.attr("src").startsWith("//voodc.com/embed/0/0/") }?.let {
                                var src = it.attr("src").substringAfter("//voodc.com/embed/0/0/")
                                val id = src.substringBefore("/")
                                val hash = src.substringAfter("/")
                                "https://voodc.com/player/d/$hash/$id"
                            }
                    } else {
                        frame
                    }
                    if (subFrameUrl != null) {
                        val source = URL(frame).host
                        val url = app.get(subFrameUrl).document.select("script")
                            .first { it.data().contains("var PlayS = '") }?.data()
                            ?.substringAfter("var PlayS = '")
                            ?.substringBefore("';")
                        if (url != null) {
                            callback(
                                newExtractorLink(
                                    "${source}",
                                    "${source}",
                                    url
                                ) {
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }

                }
            } else if (frame.startsWith("https://canalesdeportivos.net/ver/") || frame.startsWith("https://elcanaldeportivo.com/ver/")) {
                val name = frame.substringAfterLast("/").substringBefore(".php")
                val doc = app.get(frame).document
                val iframeSrc = doc.selectFirst("iframe")?.attr("src")
                if (iframeSrc != null) {
                    val resolvedUrl = if (iframeSrc.startsWith("http")) iframeSrc
                    else "https://${URL(frame).host}${iframeSrc}"
                    val fidDoc = app.get(resolvedUrl).document
                    val fid = fidDoc.select("script").firstOrNull { it.data().contains("fid=") }
                        ?.data()?.substringAfter("fid=\"")?.substringBefore("\"")
                    if (fid != null) {
                        val deepUrl = "https://deepcathink.com/deportivo.php?player=desktop&live=$fid"
                        val deepText = app.get(deepUrl, referer = frame).text
                        val m3u8Url = extractDeepCathinkUrl(deepText)
                        if (m3u8Url != null) {
                            callback(
                                newExtractorLink(
                                    "CanalesDeportivos[$name]",
                                    "CanalesDeportivos[$name]",
                                    m3u8Url,
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = "https://deepcathink.com/"
                                }
                            )
                        }
                    } else {
                        val directFrame = fidDoc.selectFirst("iframe")?.attr("src")
                        if (directFrame != null) {
                            val embedUrl = if (directFrame.startsWith("http")) directFrame
                            else "https://${URL(resolvedUrl).host}$directFrame"
                            loadExtractor(embedUrl, referer = resolvedUrl, subtitleCallback, callback)
                        }
                    }
                }

            }

        }
        return true
    }

    private fun extractDeepCathinkUrl(html: String): String? {
        val fnName = Regex("""player\.load\(\{source:\s*(\w+)\(\)""").find(html)?.groupValues?.get(1) ?: return null
        val fnDef = Regex("""function\s+$fnName\s*\(\s*\)\s*\{([^}]+)\}""").find(html)?.value ?: return null
        val arrayStr = Regex("""\[("[^"]*"(,"[^"]*")*)\]""").find(fnDef)?.value ?: return null
        val chars = Regex("\"([^\"]*)\"").findAll(arrayStr).map { it.groupValues[1] }.toList()
        val url = chars.joinToString("").replace("\\/", "/")
        return url.takeIf { it.startsWith("http") && it.contains(".m3u8") }
    }
}

data class StgruberChannelInfo(
    val url: String,
    val k1: String?,
    val k2: String?
)

data class EventData(
    val title: String,
    val hour: String,
    val urls: List<String>,
    val poster: String?,
)

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
        .replaceFirst("https://streamtp-x-y-z.ws", "https://streamtpday1.xyz")
}

fun transformHourToDate(hourString: String): Date? {
    val inputFormat = SimpleDateFormat("HH:mm", Locale.US)
    inputFormat.timeZone = TimeZone.getDefault()
    val parsedTime = inputFormat.parse(hourString) ?: return null
    val calendarToday = Calendar.getInstance()
    val calendarParsed = Calendar.getInstance().apply { time = parsedTime }
    calendarToday.set(Calendar.HOUR_OF_DAY, calendarParsed.get(Calendar.HOUR_OF_DAY))
    calendarToday.set(Calendar.MINUTE, calendarParsed.get(Calendar.MINUTE))
    calendarToday.set(Calendar.SECOND, 0)
    calendarToday.set(Calendar.MILLISECOND, 0)
    return calendarToday.time
}

fun transformHourToLocal(hourString: String, timezoneId: String? = null): String {
    val inputFormat = SimpleDateFormat("HH:mm", Locale.US)
    inputFormat.timeZone =
        if (!timezoneId.isNullOrBlank()) TimeZone.getTimeZone(timezoneId) else TimeZone.getDefault()
    val date = inputFormat.parse(hourString)
    val outputFormat = SimpleDateFormat("HH:mm", Locale.US)
    outputFormat.timeZone = TimeZone.getDefault() // current mobile timezone
    return outputFormat.format(date)
}

fun transformUtcToLocal(isoUtc: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        inputFormat.timeZone = TimeZone.getTimeZone("UTC")
        val date = inputFormat.parse(isoUtc)
        val outputFormat = SimpleDateFormat("HH:mm", Locale.US)
        outputFormat.timeZone = TimeZone.getDefault()
        outputFormat.format(date)
    } catch (e: Exception) {
        "00:00"
    }
}

fun isEventLive(startHour: String): Boolean {
    val fiveMinInMiliseconds = 600000
    val sdf = SimpleDateFormat("HH:mm", Locale.US)
    sdf.timeZone = TimeZone.getDefault() // interpret in local phone timezone
    val parsedDate = sdf.parse(startHour)
    val now = Calendar.getInstance()
    val startCal = Calendar.getInstance()
    startCal.time = parsedDate
    startCal.set(Calendar.YEAR, now.get(Calendar.YEAR))
    startCal.set(Calendar.MONTH, now.get(Calendar.MONTH))
    startCal.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
    val endCal = startCal.clone() as Calendar
    endCal.add(Calendar.HOUR_OF_DAY, 2)
    return now.timeInMillis in (startCal.timeInMillis - fiveMinInMiliseconds)..endCal.timeInMillis
}
