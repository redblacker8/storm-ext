package com.stormunblessed

import com.lagradost.cloudstream3.utils.AppUtils.toJson

/**
 * Root of https://raw.githubusercontent.com/tadeoallende/repos-web/main/datos.json
 * (the single data source behind angulismotv.pages.dev, see /scripts/inicio/main.js).
 */
data class AngulismoData(
    val events: List<AngulismoEvent>? = null,
    val channels: List<AngulismoChannel>? = null,
)

data class AngulismoEvent(
    val id: Long? = null,
    val evento: String? = null,
    val fecha: String? = null,
    val competencia: String? = null,
    val logoUrl: String? = null,
    val team1Logo: String? = null,
    val team2Logo: String? = null,
    /**
     * Entries are either a plain channel name ("ESPN") that references the global channel
     * list, or an inline `{ name, logo, url, options[] }` object. Kept as [Any] because the
     * json mixes both shapes (same as resolveEventChannels() in the site's main.js).
     */
    val canales: List<Any?>? = null,
)

data class AngulismoChannel(
    val name: String? = null,
    val logo: String? = null,
    val url: String? = null,
    val options: List<AngulismoOption>? = null,
    val show: Boolean? = null,
    val featured: Boolean? = null,
)

data class AngulismoOption(
    val name: String? = null,
    val iframe: String? = null,
)

/** One playable source of an event/channel: which channel it is and which of its options. */
data class AngulismoFeed(
    val channel: String,
    val option: String? = null,
    val url: String,
)

/** Payload handed to CloudStream as dataUrl; carries everything loadLinks() needs. */
data class AngulismoItem(
    val kind: String, // "event" | "channel"
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val hour: String? = null,
    val startMs: Long? = null,
    val featured: Boolean = false,
    val feeds: List<AngulismoFeed> = emptyList(),
) {
    fun toData(): String = this.toJson()
}
