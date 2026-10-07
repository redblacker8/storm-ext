package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.jsoup.select.Elements


class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "peliculas" to "Peliculas",
        "series" to "Series",
        "animes" to "Animes",
        "peliculas?genero=animacion&sort=popular" to "Cartoons",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val document = app.get("$mainUrl/${request.data}${separator}page=$page").document
        val home = document.select("div.card")
            .mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("a[href*='page=']:contains(›)") != null
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("div.card__info p.card__title")?.text() ?: "xd"
        val link = this.selectFirst("a")?.attr("href") ?: "xd"
        val img = this.selectFirst("a div.card__poster-wrap img.card__poster")?.attr("src")
        val type = if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, link, type) {
            this.posterUrl = img
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/buscar?q=$query").document
        val results =
            document.select("div.card").mapNotNull { it.toSearchResult() }
        return results
    }

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) :
        HashMap<String, List<MainTemporadaElement>>(elements)

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst(".w-44")!!.attr("alt")
        val poster = doc.selectFirst(".w-44")!!.attr("src")
        val backimage = doc.selectFirst(".detail-hero__bg")?.attr("style")?.substringAfter("url('")?.substringBefore("');")
        val description = doc.selectFirst("p.text-sm.leading-relaxed.max-w-2xl")!!.text()
        val year = doc.selectFirst("div.flex.flex-wrap.items-center.text-sm span")?.text()?.toIntOrNull()
        val tags = doc.select("div.flex-1.min-w-0 a[href*=/genero/]").map { it.text() }
        val recommendations = doc.select("div[id*=scroll-related] div.card").mapNotNull { it.toSearchResult() }
        val episodes = doc.select("div[data-season-panel]").flatMap {
            val season = it.attr("data-season-panel").toIntOrNull()
            it.select("a.ep-item").mapIndexed { idx, it ->
                val url = it.attr("href")
                val title = it.selectFirst("p.text-sm.font-semibold.text-white.leading-tight")?.text()
                val img = it.selectFirst("img.ep-thumb")?.attr("src")
                newEpisode(url){
                        this.name = title
                        this.season = season
                        this.episode = idx+1
                        this.posterUrl = img
                    }
            }
        }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage ?: poster
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.recommendations = recommendations
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage ?: poster
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.recommendations = recommendations
                }
            }

            else -> null
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val headers = mapOf(
            "Content-Type" to "application/json",
            "X-CSRF-TOKEN" to csrf,
            "Accept" to "application/json",
        )
        // Language groups ("mx" -> Latino) each hold their own server buttons
        val langGroups: List<Pair<String, Elements>> = doc.select("div[data-lang-group]")
            .mapNotNull { group ->
                val buttons = group.select("button.server-btn")
                if (buttons.isEmpty()) null else group.attr("data-lang-group") to buttons
            }
            .takeIf { it.isNotEmpty() }
            ?: listOf(lang to doc.select("button.server-btn"))

        langGroups.forEach { (groupLang, buttons) ->
            buttons.amap { btn ->
                val serverName = btn.text()
                val token = btn.attr("data-player-token")
                if (token.isNotBlank()) {
                    val response = app.post(
                        "$mainUrl/api/player-url",
                        headers = headers,
                        data = mapOf("t" to token)
                    ).parsedSafe<PlayerResponse>()
                    response?.let { resolved ->
                        val url = resolved.url
                        when {
                            resolved.type == "mp4" -> callback.invoke(
                                newExtractorLink(
                                    normalizeSourceServer(serverName),
                                    standardSourceName(groupLang, serverName),
                                    url
                                )
                            )
                            url.startsWith("https://embed69.org/") -> {
                                Embed69Extractor.load(url, data, subtitleCallback, callback)
                            }
                            url.startsWith("https://xupalace.org/video") -> {
                                val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                                regex.findAll(app.get(url).document.html()).map { it.groupValues[2] }
                                    .toList().amap {
                                        loadSourceNameExtractor(groupLang, it, data, subtitleCallback, callback)
                                    }
                            }
                            else -> {
                                app.get(url).document.selectFirst("iframe")?.attr("src")?.let {
                                    loadSourceNameExtractor(groupLang, it, data, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}

data class PlayerResponse(
    val url: String = "",
    val type: String = "",
)