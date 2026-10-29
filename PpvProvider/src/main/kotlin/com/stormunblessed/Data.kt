package com.stormunblessed

// api.<mirror>/api/streams -> { success, timestamp, READ_ME, performance, streams: [...] }
data class PpvStreamsResponse(
    val success: Boolean? = null,
    val streams: List<PpvCategory>? = null
)

/** One category block of the catalog: its rows live in [streams]. */
data class PpvCategory(
    val category: String? = null,
    val id: Int? = null,
    val always_live: Boolean? = null,
    val streams: List<PpvStream>? = null
)

/** One row of the catalog (event or 24/7 channel). */
data class PpvStream(
    val id: Int? = null,
    val name: String? = null,
    val tag: String? = null,
    val source_tag: String? = null,
    val poster: String? = null,
    val uri_name: String? = null,
    val starts_at: Long? = null,
    val ends_at: Long? = null,
    val always_live: Int? = null,
    val locale: String? = null,
    val category_name: String? = null,
    val iframe: String? = null,
    val substreams: List<PpvSubstream>? = null,
    // the list endpoint sends this as a string ("5"), the detail endpoint as a number
    val viewers: String? = null
)

/** Alternate feed of an event (SkyCast, All-22, ...): same event, different player frame. */
data class PpvSubstream(
    val id: Int? = null,
    val name: String? = null,
    val tag: String? = null,
    val uri_name: String? = null,
    val source_tag: String? = null,
    val locale: String? = null,
    val iframe: String? = null
)

/**
 * Compact snapshot of a catalog row embedded as the `dataUrl`, so load()/loadLinks() never have
 * to re-query the catalog. Same trick as StreamedProvider's APIMatch json.
 */
data class PpvItem(
    val name: String? = null,
    val tag: String? = null,
    val source_tag: String? = null,
    val locale: String? = null,
    val poster: String? = null,
    val uri_name: String? = null,
    val starts_at: Long? = null,
    val ends_at: Long? = null,
    val always_live: Int? = null,
    val category_name: String? = null,
    val iframe: String? = null,
    val substreams: List<PpvSubstream>? = null,
    val viewers: Int? = null
)

fun PpvStream.toItem() = PpvItem(
    name = name,
    tag = tag,
    source_tag = source_tag,
    locale = locale,
    poster = poster,
    uri_name = uri_name,
    starts_at = starts_at,
    ends_at = ends_at,
    always_live = always_live,
    category_name = category_name,
    iframe = iframe,
    substreams = substreams,
    viewers = viewers?.trim()?.toIntOrNull()
)

/** 24/7 channels are always on; events only inside their kickoff window. */
fun PpvItem.isLiveAt(now: Long): Boolean {
    if (always_live == 1) return true
    val start = starts_at ?: return false
    if (start <= 0L) return false
    val end = ends_at ?: (start + 3 * 60 * 60)
    return now in start..end
}

// api.<mirror>/api/streams/<uri_name> -> { success, data: { ... } }
data class PpvDetailResponse(
    val success: Boolean? = null,
    val data: PpvDetail? = null
)

data class PpvDetail(
    val name: String? = null,
    val description: String? = null,
    val m3u8: String? = null,
    val source_type: String? = null,
    val sources: List<PpvDetailSource>? = null,
    val source_tag: String? = null,
    val locale: String? = null
)

/** `type` is "iframe" (external player frame) or a direct playlist; `data` holds the url. */
data class PpvDetailSource(
    val name: String? = null,
    val type: String? = null,
    val data: String? = null
)
