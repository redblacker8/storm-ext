package com.stormunblessed

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * CubeEmbed (cubeembed.rpmvid.com) resolves video hashes via its /api/public/resolve endpoint
 * to upstream embeds (typically turboembed.com). This extractor handles the full chain:
 *   1. Resolve the hash → upstream embed URL
 *   2. Fetch download tiers from the upstream embed
 *   3. Obtain the direct playable URL from the upstream download endpoint
 */
class Rpmvid : ExtractorApi() {
    override var name = "Rpmvid"
    override var mainUrl = "https://cubeembed.rpmvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Step 1: Extract hash from the URL fragment (#hash)
        val hash = url.substringAfterLast("#")
        if (hash.isEmpty()) return

        // Step 2: Call cubeembed resolve API to get the upstream embed URL
        val resolveResp = app.get("$mainUrl/api/public/resolve?file1=$hash").text
        val resolvedUrl = Regex(""""url":"([^"]+)"""").find(resolveResp)?.groupValues?.get(1) ?: return

        // Step 3: Extract the file ID from the upstream URL (e.g. "g1av" from "https://turboembed.com/g1av")
        val fileId = resolvedUrl.trimEnd('/').substringAfterLast("/")

        // Step 4: Fetch download tiers from the upstream embed API
        val tiersResp = app.get("https://turboembed.com/api/download/tiers?s=$fileId").text

        // Pick the last tier (highest quality) or fall back to the first one
        val bestTier = Regex(""""file":"([^"]+)"""").findAll(tiersResp)
            .lastOrNull()?.groupValues?.get(1)
            ?: Regex(""""file":"([^"]+)"""").find(tiersResp)?.groupValues?.get(1)
            ?: return

        // Step 5: Call the download endpoint — app.get follows the 302 redirect,
        // so response.url holds the final playable URL
        val downloadUrl = "https://turboembed.com/api/download/get?s=$fileId&t=$bestTier"
        val finalUrl = app.get(downloadUrl).url

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                type = INFER_TYPE,
            ) {
                this.referer = "https://turboembed.com/"
            }
        )
    }
}

class Sendvid : ExtractorApi() {
    override var name = "Sendvid"
    override val mainUrl = "https://sendvid.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val urlString = doc.select("head meta[property=og:video:secure_url]").attr("content")
        if (urlString.contains("m3u8")) {
            generateM3u8(
                name,
                urlString,
                mainUrl,
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = urlString,
                    type = INFER_TYPE
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}