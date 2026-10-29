package com.stormunblessed

val defaultPoster: String = "data:image/webp;base64,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"

// sportsbite.org GET /api/forestgump/matches -> { days: [ { date, events: [...] } ] }
data class SportsBiteMatches(
    val days: List<MatchDay>? = null
)

data class MatchDay(
    val date: String? = null,
    val events: List<APIMatch>? = null
)

data class APIMatch(
    val title: String? = null,
    val slug: String? = null,
    val category: String? = null,
    val sport: String? = null,
    val start: String? = null,
    val kickoffMs: Long? = null,
    val poster: String? = null,
    val popular: Boolean? = null,
    val live: Boolean? = null,
    val streamed_event_id: String? = null,
    val event_key: String? = null,
    val teams: Teams? = null,
    val streams: List<SBStream>? = null,
)

/** Stable id used to key /api/event-images/batch results. */
fun APIMatch.imageId(): String = streamed_event_id ?: event_key ?: slug ?: title.orEmpty()

data class SBStream(
    val id: String? = null,
    val name: String? = null,
    val label: String? = null,
    val manifest_url: String? = null,
    val format: String? = null,
    val quality: String? = null,
    val language: String? = null,
    val source: String? = null,
    val status: String? = null,
    val viewers: Int? = null,
)

data class Teams(
    val home: TeamInfo? = null,
    val away: TeamInfo? = null
)

data class TeamInfo(
    val name: String? = null,
    val badge: String? = null
)

// sportsbite.org POST /api/event-images/batch -> { images: { <id>: { poster, homeLogo, awayLogo, leagueLogo } } }
data class EventImagesRequest(
    val events: List<EventImageRequest>? = null
)

data class EventImageRequest(
    val id: String? = null,
    val title: String? = null,
    val league: String? = null,
    val sport: String? = null,
    val home: String? = null,
    val away: String? = null,
)

data class EventImagesResponse(
    val images: Map<String, EventImage>? = null
)

data class EventImage(
    val poster: String? = null,
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    val leagueLogo: String? = null,
)

