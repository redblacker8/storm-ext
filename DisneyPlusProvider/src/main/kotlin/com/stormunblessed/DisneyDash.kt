package com.stormunblessed

import android.util.Base64
import com.lagradost.cloudstream3.app
import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Disney+ only exposes HLS (SAMPLE-AES-CTR + Widevine), but CloudStream's player
 * creates a DashMediaSource for every DRM link. This converts the HLS master
 * playlist into a DASH manifest (video + every audio rendition) so the player
 * can consume it and offer audio-track selection.
 *
 * Subtitles are HLS WebVTT segments; they are combined on demand and served by
 * [DisneyPlusProvider.getVideoInterceptor] through a proxy URL.
 */
internal object DisneyDash {

    data class SubtitleRendition(val name: String, val lang: String, val playlistUrl: String)

    private data class Segment(val url: String, val durationMs: Long, val time: Long?)

    private data class Track(
        val initUrl: String,
        val segments: List<Segment>,
        val bandwidth: Long,
        val lang: String? = null,
    )

    suspend fun buildManifest(hlsUrl: String, preferredAudio: String?): String? {
        val master = fetch(hlsUrl) ?: return null
        val lines = master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        var pssh: String? = null
        var kid: String? = null
        for (line in lines) {
            if (!line.startsWith("#EXT-X-SESSION-KEY:")) continue
            if (!line.contains(WIDEVINE_KEYFORMAT)) continue
            val data = attr(line, "URI")?.substringAfter("base64,", "")?.trim().orEmpty()
            if (data.isNotEmpty()) {
                pssh = data
                kid = parseKid(data)
            }
            break
        }

        var variantUri: String? = null
        var audioGroup: String? = null
        var codecs: String? = null
        var resolution: String? = null
        var variantBandwidth = 0L
        var score = Long.MIN_VALUE
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val bandwidth = attr(line, "BANDWIDTH")?.toLongOrNull() ?: 0L
            val next = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: continue
            val group = attr(line, "AUDIO")
            // Prefer AAC stereo audio over E-AC-3 for device compatibility.
            val rank = bandwidth + if (group?.contains("aac", ignoreCase = true) == true) 100_000_000L else 0L
            if (rank <= score) continue
            score = rank
            variantUri = next
            audioGroup = group
            codecs = attr(line, "CODECS")?.substringBefore(",")?.trim()
            resolution = attr(line, "RESOLUTION")
            variantBandwidth = bandwidth
        }
        if (variantUri == null) return null

        val videoUrl = resolve(hlsUrl, variantUri)
        val video = parseTrack(videoUrl, fetch(videoUrl) ?: return null, variantBandwidth) ?: return null

        val audioLines = lines.filter {
            it.startsWith("#EXT-X-MEDIA:") &&
                attr(it, "TYPE") == "AUDIO" &&
                (audioGroup == null || attr(it, "GROUP-ID") == audioGroup) &&
                !attr(it, "URI").isNullOrBlank()
        }
        val audioTracks = audioLines.mapNotNull { line ->
            val uri = attr(line, "URI") ?: return@mapNotNull null
            val url = resolve(hlsUrl, uri)
            val track = fetch(url)?.let { parseTrack(url, it, 128_000L) } ?: return@mapNotNull null
            track.copy(lang = attr(line, "LANGUAGE"))
        }.sortedBy { audioPreference(it.lang, preferredAudio) }

        return buildMpd(
            video = video,
            videoCodecs = codecs,
            resolution = resolution,
            audioTracks = audioTracks,
            pssh = pssh,
            kid = kid,
        )
    }

    suspend fun buildSubtitles(hlsUrl: String): List<SubtitleRendition> {
        val master = fetch(hlsUrl) ?: return emptyList()
        return master.lineSequence().map { it.trim() }.filter {
            it.startsWith("#EXT-X-MEDIA:") &&
                attr(it, "TYPE") == "SUBTITLES" &&
                !attr(it, "URI").isNullOrBlank()
        }.mapNotNull { line ->
            val uri = attr(line, "URI") ?: return@mapNotNull null
            val lang = attr(line, "LANGUAGE") ?: return@mapNotNull null
            SubtitleRendition(attr(line, "NAME") ?: lang, lang, resolve(hlsUrl, uri))
        }.toList()
    }

    /** Fetches an HLS WebVTT playlist and merges its segments into one document. */
    suspend fun buildSubtitleVtt(playlistUrl: String): String? {
        val playlist = fetch(playlistUrl) ?: return null
        val sections = parseVttSections(playlistUrl, playlist)
        val best = sections.maxByOrNull { section -> section.sumOf { it.second } } ?: return null
        val out = StringBuilder("WEBVTT\n\n")
        for ((url, _) in best) {
            val vtt = fetch(url) ?: continue
            val cues = cuesOnly(vtt)
            if (cues.isNotBlank()) out.append(cues).append("\n\n")
        }
        return out.toString()
    }

    private suspend fun fetch(url: String): String? =
        runCatching {
            app.get(url, headers = mapOf("User-Agent" to DisneyConstants.userAgent())).text
        }.getOrNull()

    private fun parseTrack(baseUrl: String, text: String, bandwidth: Long): Track? {
        var sectionInit: String? = null
        var pendingMs: Long? = null
        var current = mutableListOf<Segment>()
        val sections = mutableListOf<Pair<String, List<Segment>>>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-DISCONTINUITY") -> {
                    sectionInit?.let { init ->
                        if (current.isNotEmpty()) sections.add(init to current)
                    }
                    current = mutableListOf()
                    sectionInit = null
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    if (current.isEmpty()) {
                        sectionInit = attr(line, "URI")?.let { resolve(baseUrl, it) }
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    pendingMs = line.removePrefix("#EXTINF:")
                        .substringBefore(",")
                        .toDoubleOrNull()
                        ?.times(1000.0)
                        ?.toLong()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val duration = pendingMs ?: continue
                    val url = resolve(baseUrl, line)
                    val time = PTS_REGEX.find(url)?.groupValues?.get(1)?.toLongOrNull()
                    current.add(Segment(url, duration, time))
                    pendingMs = null
                }
            }
        }
        sectionInit?.let { init ->
            if (current.isNotEmpty()) sections.add(init to current)
        }

        // Interstitials/ads create extra discontinuity sections with their own init
        // and reset PTS. Keep the longest section (the actual title).
        val best = sections.maxByOrNull { (_, segments) -> segments.sumOf { it.durationMs } } ?: return null
        return Track(best.first, best.second, bandwidth)
    }

    private fun parseVttSections(baseUrl: String, text: String): List<List<Pair<String, Double>>> {
        var pending: Double? = null
        var current = mutableListOf<Pair<String, Double>>()
        val sections = mutableListOf<List<Pair<String, Double>>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-DISCONTINUITY") -> {
                    if (current.isNotEmpty()) sections.add(current)
                    current = mutableListOf()
                }
                line.startsWith("#EXTINF:") -> {
                    pending = line.removePrefix("#EXTINF:").substringBefore(",").toDoubleOrNull()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val duration = pending ?: continue
                    current.add(resolve(baseUrl, line) to duration)
                    pending = null
                }
            }
        }
        if (current.isNotEmpty()) sections.add(current)
        return sections
    }

    private fun cuesOnly(vtt: String): String {
        val lines = vtt.lines()
        val start = lines.indexOfFirst { CUE_TIME.matches(it.trim()) }
        if (start < 0) return ""
        return lines.drop(start).joinToString("\n").trim()
    }

    private fun buildMpd(
        video: Track,
        videoCodecs: String?,
        resolution: String?,
        audioTracks: List<Track>,
        pssh: String?,
        kid: String?,
    ): String {
        val duration = String.format(Locale.US, "PT%.3fS", videoDurationMs(video) / 1000.0)
        val (width, height) = parseResolution(resolution)
        val out = StringBuilder(48 * 1024)
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        out.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:cenc=\"urn:mpeg:cenc:2013\" ")
        out.append("type=\"static\" mediaPresentationDuration=\"").append(duration).append("\" ")
        out.append("minBufferTime=\"PT2S\" profiles=\"urn:mpeg:dash:profile:isoff-main:2011\">\n<Period>\n")
        appendTrack(out, video, "video", "video/mp4", videoCodecs, width, height, null, pssh, kid, "video")
        audioTracks.forEachIndexed { index, track ->
            appendTrack(
                out, track, "audio", "audio/mp4", "mp4a.40.2",
                null, null, track.lang, pssh, kid, "audio_$index",
            )
        }
        out.append("</Period>\n</MPD>\n")
        return out.toString()
    }

    private fun videoDurationMs(track: Track): Long {
        val last = track.segments.last()
        val lastTime = last.time ?: return track.segments.sumOf { it.durationMs }
        val firstTime = track.segments.first().time ?: 0L
        val end = lastTime + (last.durationMs * TIMESCALE / 1000.0).roundToLong()
        return ((end - firstTime) * 1000.0 / TIMESCALE).roundToLong()
    }

    private fun appendTrack(
        out: StringBuilder,
        track: Track,
        contentType: String,
        mimeType: String,
        codecs: String?,
        width: Int?,
        height: Int?,
        lang: String?,
        pssh: String?,
        kid: String?,
        id: String,
    ) {
        out.append("<AdaptationSet contentType=\"").append(contentType)
            .append("\" mimeType=\"").append(mimeType).append('"')
        codecs?.takeIf { it.isNotBlank() }?.let { out.append(" codecs=\"").append(escape(it)).append('"') }
        width?.let { out.append(" width=\"").append(it).append('"') }
        height?.let { out.append(" height=\"").append(it).append('"') }
        lang?.takeIf { it.isNotBlank() }?.let { out.append(" lang=\"").append(escape(it)).append('"') }
        out.append(">\n")

        if (kid != null) {
            out.append("<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" ")
                .append("value=\"cbcs\" cenc:default_KID=\"").append(kid).append("\"/>\n")
            out.append("<ContentProtection schemeIdUri=\"").append(WIDEVINE_KEYFORMAT).append("\" ")
                .append("cenc:default_KID=\"").append(kid).append("\">")
            pssh?.let { out.append("<cenc:pssh>").append(it).append("</cenc:pssh>") }
            out.append("</ContentProtection>\n")
        }

        // Media3 only builds track formats from explicit Representation elements.
        out.append("<Representation id=\"").append(id)
            .append("\" bandwidth=\"").append(track.bandwidth.coerceAtLeast(1L))
            .append("\" mimeType=\"").append(mimeType).append('"')
        codecs?.takeIf { it.isNotBlank() }?.let { out.append(" codecs=\"").append(escape(it)).append('"') }
        width?.let { out.append(" width=\"").append(it).append('"') }
        height?.let { out.append(" height=\"").append(it).append('"') }
        out.append(">\n")

        val segments = track.segments
        val timescale = TIMESCALE
        val template = segmentTemplate(track)
        if (template != null) {
            out.append("<SegmentTemplate timescale=\"").append(timescale)
                .append("\" initialization=\"").append(escape(track.initUrl))
                .append("\" media=\"").append(escape(template)).append("\">\n")
            appendTimeline(out, segments, timescale)
            out.append("</SegmentTemplate>\n")
        } else {
            out.append("<SegmentList timescale=\"").append(timescale).append("\">\n")
            out.append("<Initialization sourceURL=\"").append(escape(track.initUrl)).append("\"/>\n")
            appendTimeline(out, segments, timescale)
            for (segment in segments) {
                out.append("<SegmentURL media=\"").append(escape(segment.url)).append("\"/>\n")
            }
            out.append("</SegmentList>\n")
        }
        out.append("</Representation>\n")
        out.append("</AdaptationSet>\n")
    }

    private fun appendTimeline(out: StringBuilder, segments: List<Segment>, timescale: Int) {
        out.append("<SegmentTimeline>\n")
        var cumulative = 0L
        for (index in segments.indices) {
            val segment = segments[index]
            val time = segment.time ?: cumulative
            val next = segments.getOrNull(index + 1)?.time
            val duration = if (next != null && next > time) {
                next - time
            } else {
                (segment.durationMs * timescale / 1000.0).roundToLong()
            }
            out.append("<S t=\"").append(time).append("\" d=\"").append(duration).append("\"/>\n")
            cumulative = time + duration
        }
        out.append("</SegmentTimeline>\n")
    }

    /**
     * Every segment in the chosen section is normally `<dir>/pts_<time>.<ext>`,
     * so a $Time$ template keeps the MPD small even with many audio languages.
     */
    private fun segmentTemplate(track: Track): String? {
        val segments = track.segments
        if (segments.size < 2) return null
        val first = segments[0].url
        val slash = first.lastIndexOf('/')
        if (slash <= 0) return null
        val prefix = first.substring(0, slash)
        val file = first.substring(slash + 1)
        val dot = file.lastIndexOf('.')
        val extension = if (dot >= 0) file.substring(dot) else ""
        for (segment in segments) {
            val time = segment.time ?: return null
            if (segment.url != "$prefix/pts_$time$extension") return null
        }
        return "$prefix/pts_\$Time\$$extension"
    }

    private fun audioPreference(lang: String?, preferred: String?): Int {
        if (lang == null) return 3
        val value = lang.lowercase(Locale.US)
        val wanted = preferred?.lowercase(Locale.US)
        val base = wanted?.substringBefore("-")
        return when {
            wanted != null && value == wanted -> 0
            base != null && base.isNotEmpty() && value.startsWith(base) -> 1
            value.startsWith("en") -> 2
            else -> 3
        }
    }

    private fun attr(line: String, name: String): String? {
        val match = Regex("(?:^|[,:])" + Regex.escape(name) + "=(\"([^\"]*)\"|([^,]*))").find(line)
            ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[3] }.trim()
    }

    private fun resolve(base: String, reference: String): String =
        runCatching { URI(base).resolve(reference).toString() }.getOrDefault(reference)

    private fun parseResolution(value: String?): Pair<Int?, Int?> {
        val parts = value?.lowercase(Locale.US)?.split("x") ?: return null to null
        if (parts.size != 2) return null to null
        return parts[0].toIntOrNull() to parts[1].toIntOrNull()
    }

    /** Widevine PSSH v0: size|"pssh"|ver|flags|systemId|dataSize|0x12 0x10|KID|data */
    private fun parseKid(psshBase64: String): String? {
        val bytes = runCatching { Base64.decode(psshBase64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size < 50) return null
        if (bytes[32] != 0x12.toByte() || bytes[33] != 0x10.toByte()) return null
        fun hex(index: Int) = String.format(Locale.US, "%02x", bytes[34 + index].toInt() and 0xff)
        return buildString(36) {
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                append(hex(i))
            }
        }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private val PTS_REGEX = Regex("pts_(\\d+)(?:\\.|$)")
    private val CUE_TIME = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+-->.*")

    private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    /** Disney CMAF segments use a 48 kHz media timescale. */
    private const val TIMESCALE = 48000
}
