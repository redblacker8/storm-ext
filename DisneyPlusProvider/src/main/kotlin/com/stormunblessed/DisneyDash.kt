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

    private data class WidevineKey(
        val pssh: String,
        val kid: String?,
        /** "cenc" for SAMPLE-AES-CTR, "cbcs" for SAMPLE-AES. */
        val scheme: String,
    )

    private data class Track(
        val initUrl: String,
        val segments: List<Segment>,
        val bandwidth: Long,
        val lang: String? = null,
        val key: WidevineKey? = null,
        /** #EXT-X-PROGRAM-DATE-TIME of the chosen section's first segment. */
        val pdt: String? = null,
    )

    private data class Section(
        val initUrl: String,
        val segments: List<Segment>,
        val pdt: String?,
    )

    /** A built DASH manifest plus the live anchor values the provider must cache. */
    data class BuiltManifest(
        val xml: String,
        val live: Boolean,
        val availabilityStartTime: String? = null,
        val presentationTimeOffset: Long? = null,
    )

    /**
     * Everything needed to publish a live (dynamic) DASH manifest. The anchor values
     * stay fixed for the whole presentation while the segment window slides forward.
     */
    private data class LiveConfig(
        val availabilityStartTime: String,
        val presentationTimeOffset: Long,
        val timeShiftBufferDepthSeconds: String,
        val minimumUpdatePeriodSeconds: String,
    )

    suspend fun buildManifest(
        hlsUrl: String,
        preferredAudio: String?,
        direct: Boolean = false,
        anchorStartTime: String? = null,
        anchorTimeOffset: Long? = null,
        windowSeconds: Long = LIVE_WINDOW_SECONDS,
    ): BuiltManifest? {
        val master = fetch(hlsUrl, direct) ?: return null
        val lines = master.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        // VOD masters carry #EXT-X-SESSION-KEY, but live/linear streams usually
        // put the Widevine key in each media playlist instead. Keep the master key
        // as a fallback and only mark tracks that actually carry one.
        val masterKey = findWidevineKey(master)

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
        val videoText = fetch(videoUrl, direct) ?: return null
        val live = !videoText.contains("#EXT-X-ENDLIST")
        val videoKey = findWidevineKey(videoText) ?: masterKey
        val video = parseTrack(videoUrl, videoText, variantBandwidth, keepLast = live)
            ?.copy(key = videoKey)
            ?: return null

        val audioLines = lines.filter {
            it.startsWith("#EXT-X-MEDIA:") &&
                attr(it, "TYPE") == "AUDIO" &&
                (audioGroup == null || attr(it, "GROUP-ID") == audioGroup) &&
                !attr(it, "URI").isNullOrBlank()
        }
        val audioTracks = audioLines.mapNotNull { line ->
            val uri = attr(line, "URI") ?: return@mapNotNull null
            val url = resolve(hlsUrl, uri)
            val text = fetch(url, direct) ?: return@mapNotNull null
            val audioKey = findWidevineKey(text) ?: masterKey
            val track = parseTrack(url, text, 128_000L, keepLast = !text.contains("#EXT-X-ENDLIST"))
                ?.copy(lang = attr(line, "LANGUAGE"), key = audioKey)
                ?: return@mapNotNull null
            track
        }.sortedBy { audioPreference(it.lang, preferredAudio) }

        // For live playlists keep the anchor fixed for the whole presentation and
        // slide the segment window so each refreshed manifest stays small.
        val anchorPdt = if (live) anchorStartTime ?: video.pdt else null
        val anchorTime = if (live) anchorTimeOffset ?: video.segments.firstOrNull()?.time else null
        val windowVideo = if (live) trimToWindow(video, windowSeconds) else video
        val windowAudio = if (live) audioTracks.map { trimToWindow(it, windowSeconds) } else audioTracks

        val liveConfig = if (live && anchorTime != null) {
            LiveConfig(
                availabilityStartTime = anchorPdt ?: nowIso(),
                presentationTimeOffset = anchorTime,
                timeShiftBufferDepthSeconds = bufferDurationSeconds(windowVideo),
                minimumUpdatePeriodSeconds = segmentDurationSeconds(windowVideo),
            )
        } else null

        val xml = buildMpd(
            video = windowVideo,
            videoCodecs = codecs,
            resolution = resolution,
            audioTracks = windowAudio,
            live = liveConfig,
        )
        return BuiltManifest(
            xml = xml,
            live = live,
            availabilityStartTime = liveConfig?.availabilityStartTime,
            presentationTimeOffset = liveConfig?.presentationTimeOffset,
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

    private suspend fun fetch(url: String, direct: Boolean = false): String? {
        if (direct) return fetchDirect(url)
        return runCatching {
            app.get(url, headers = mapOf("User-Agent" to DisneyConstants.userAgent())).text
        }.getOrNull()
    }

    /**
     * Fetches a playlist without going through the plugin's OkHttp client. Used when a
     * live manifest must be rebuilt from inside [DisneyPlusProvider.getVideoInterceptor],
     * where re-entering the same client would return the cached manifest instead of the
     * real HLS playlist.
     */
    private fun fetchDirect(url: String): String? = runCatching {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", DisneyConstants.userAgent())
        connection.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun parseTrack(baseUrl: String, text: String, bandwidth: Long, keepLast: Boolean = false): Track? {
        var sectionInit: String? = null
        var sectionPdt: String? = null
        var pendingMs: Long? = null
        var pendingPdt: String? = null
        var current = mutableListOf<Segment>()
        val sections = mutableListOf<Section>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                    if (pendingPdt == null) {
                        pendingPdt = line.removePrefix("#EXT-X-PROGRAM-DATE-TIME:").trim()
                    }
                }
                line.startsWith("#EXT-X-DISCONTINUITY") -> {
                    sectionInit?.let { init ->
                        if (current.isNotEmpty()) sections.add(Section(init, current, sectionPdt))
                    }
                    current = mutableListOf()
                    sectionInit = null
                    sectionPdt = null
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
                    if (sectionPdt == null) sectionPdt = pendingPdt
                    val url = resolve(baseUrl, line)
                    current.add(Segment(url, duration, segmentTime(url)))
                    pendingMs = null
                }
            }
        }
        sectionInit?.let { init ->
            if (current.isNotEmpty()) sections.add(Section(init, current, sectionPdt))
        }

        // For live playlists the last discontinuity section is the current
        // program; for VOD keep the longest section (the actual title).
        val best = if (keepLast) {
            sections.lastOrNull()
        } else {
            sections.maxByOrNull { it.segments.sumOf { s -> s.durationMs } }
        } ?: return null
        return Track(best.initUrl, best.segments, bandwidth, pdt = best.pdt)
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
        live: LiveConfig? = null,
    ): String {
        val (width, height) = parseResolution(resolution)
        val out = StringBuilder(48 * 1024)
        out.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        out.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:cenc=\"urn:mpeg:cenc:2013\" ")
        if (live != null) {
            out.append("type=\"dynamic\" ")
            out.append("availabilityStartTime=\"").append(live.availabilityStartTime).append("\" ")
            out.append("publishTime=\"").append(nowIso()).append("\" ")
            out.append("minimumUpdatePeriod=\"PT").append(live.minimumUpdatePeriodSeconds).append("S\" ")
            out.append("timeShiftBufferDepth=\"PT").append(live.timeShiftBufferDepthSeconds).append("S\" ")
            out.append("suggestedPresentationDelay=\"PT6S\" ")
        } else {
            val duration = String.format(Locale.US, "PT%.3fS", videoDurationMs(video) / 1000.0)
            out.append("type=\"static\" mediaPresentationDuration=\"").append(duration).append("\" ")
        }
        out.append("minBufferTime=\"PT2S\" profiles=\"urn:mpeg:dash:profile:isoff-main:2011\">\n")
        out.append(if (live != null) "<Period id=\"0\" start=\"PT0S\">\n" else "<Period>\n")
        appendTrack(out, video, "video", "video/mp4", videoCodecs, width, height, null, video.key, "video", live)
        audioTracks.forEachIndexed { index, track ->
            appendTrack(
                out, track, "audio", "audio/mp4", "mp4a.40.2",
                null, null, track.lang, track.key, "audio_$index", live,
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

    /** Median segment duration in seconds, used as the live minimumUpdatePeriod. */
    private fun segmentDurationSeconds(track: Track): String {
        val durations = track.segments.map { it.durationMs }.sorted()
        val median = durations.getOrNull(durations.size / 2) ?: 6_000L
        return String.format(Locale.US, "%.3f", median / 1000.0)
    }

    /** Full DVR window length in seconds, used as the live timeShiftBufferDepth. */
    private fun bufferDurationSeconds(track: Track): String =
        String.format(Locale.US, "%.3f", videoDurationMs(track) / 1000.0)

    /** Current UTC time as an ISO-8601 instant, used as the live publishTime. */
    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    /**
     * Trims a live track down to its most recent [windowSeconds] seconds so the
     * refreshed DASH manifest stays small. Timeline times are absolute, so the
     * presentation anchor does not move when the window slides.
     */
    private fun trimToWindow(track: Track, windowSeconds: Long): Track {
        if (windowSeconds <= 0) return track
        val segments = track.segments
        if (segments.size < 2) return track
        val end = segments.last().time ?: return track
        val cutoff = end - windowSeconds * TIMESCALE
        val trimmed = segments.filter { (it.time ?: 0L) >= cutoff }
        if (trimmed.size < 2) return track
        return track.copy(segments = trimmed)
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
        key: WidevineKey?,
        id: String,
        live: LiveConfig? = null,
    ) {
        out.append("<AdaptationSet contentType=\"").append(contentType)
            .append("\" mimeType=\"").append(mimeType).append('"')
        codecs?.takeIf { it.isNotBlank() }?.let { out.append(" codecs=\"").append(escape(it)).append('"') }
        width?.let { out.append(" width=\"").append(it).append('"') }
        height?.let { out.append(" height=\"").append(it).append('"') }
        lang?.takeIf { it.isNotBlank() }?.let { out.append(" lang=\"").append(escape(it)).append('"') }
        out.append(">\n")

        if (key != null) {
            out.append("<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"")
                .append(key.scheme).append('"')
            key.kid?.let { out.append(" cenc:default_KID=\"").append(it).append('"') }
            out.append("/>\n")
            out.append("<ContentProtection schemeIdUri=\"").append(WIDEVINE_KEYFORMAT).append('"')
            key.kid?.let { out.append(" cenc:default_KID=\"").append(it).append('"') }
            out.append('>')
            out.append("<cenc:pssh>").append(key.pssh).append("</cenc:pssh>")
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
                .append("\" media=\"").append(escape(template)).append('"')
            if (live != null) {
                out.append(" presentationTimeOffset=\"").append(live.presentationTimeOffset).append('"')
            }
            out.append(">\n")
            appendTimeline(out, segments, timescale)
            out.append("</SegmentTemplate>\n")
        } else {
            out.append("<SegmentList timescale=\"").append(timescale).append('"')
            if (live != null) {
                out.append(" presentationTimeOffset=\"").append(live.presentationTimeOffset).append('"')
            }
            out.append(">\n")
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

    /** Finds the Widevine key (PSSH + KID) in an HLS master or media playlist. */
    private fun findWidevineKey(playlist: String): WidevineKey? {
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-KEY:") && !line.startsWith("#EXT-X-SESSION-KEY:")) continue
            if (!line.contains(WIDEVINE_KEYFORMAT)) continue
            val uri = attr(line, "URI") ?: continue
            if (!uri.contains("base64,")) continue
            val data = uri.substringAfter("base64,", "").trim()
            if (data.isEmpty()) continue
            val scheme = if (line.contains("SAMPLE-AES-CTR", ignoreCase = true)) "cenc" else "cbcs"
            return WidevineKey(data, parseKid(data), scheme)
        }
        return null
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
    private val TIMECODE_REGEX = Regex("/(\\d{2})/(\\d{2})/(\\d{2})_(\\d{3})\\.[A-Za-z0-9]+$")
    private val CUE_TIME = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+-->.*")

    /**
     * Media time of a segment in 48 kHz ticks. Disney uses two URL shapes:
     * `pts_<ticks>.mp4` and wall-clock timecodes like `309/04/59/56_387.mp4`
     * (HH/MM/SS_mmm). The timecode form is converted to ticks since midnight so
     * the DASH timeline stays stable across live manifest refreshes.
     */
    private fun segmentTime(url: String): Long? {
        PTS_REGEX.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        val match = TIMECODE_REGEX.find(url) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millis = match.groupValues[4].toLongOrNull() ?: return null
        val totalMs = ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
        return totalMs * TIMESCALE / 1000
    }

    private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    /** Disney CMAF segments use a 48 kHz media timescale. */
    private const val TIMESCALE = 48000

    /** How much DVR history to keep in a refreshed live manifest. */
    private const val LIVE_WINDOW_SECONDS = 300L
}
