package com.stormunblessed.settings

import android.content.SharedPreferences

/**
 * Source filter shared by the catalog providers (CatalogoInfantil,
 * CatalogoNetflix, CatalogoHboMax, CatalogoGeneral).
 *
 * Storage is a blacklist, so the default state (no stored names) means
 * "everything allowed": every installed provider can be used to find links.
 * There are two additive levels:
 *
 *  - global ([GLOBAL_KEY]): the provider is blocked in every catalog.
 *  - own ([ownKey]): the provider is blocked only in this catalog.
 *
 * The effective list is the union of both, so a global block cannot be undone
 * from a single catalog; a catalog can only add blocks. This keeps the state
 * unambiguous when settings are edited from different providers.
 *
 * All catalog plugins use the same preferences file ([PREFS_NAME]) so the
 * global level is actually common. Values are read from [SharedPreferences] on
 * every query (an in-memory read), so a change made from one catalog's panel is
 * picked up immediately by the other installed catalogs.
 */
class CatalogoSourceFilter(
    private val prefs: SharedPreferences,
    val catalogKey: String,
) {
    companion object {
        /** Preferences file shared by every catalog plugin. */
        const val PREFS_NAME = "CatalogoSourceFilter"

        /** Catalog ids; must match the [catalogKey] passed by each plugin. */
        const val KEY_INFANTIL = "infantil"
        const val KEY_NETFLIX = "netflix"
        const val KEY_HBOMAX = "hbomax"
        const val KEY_GENERAL = "general"

        /** Providers blocked in every catalog. */
        const val GLOBAL_KEY = "blocked_global"

        private const val OWN_PREFIX = "blocked_"

        private fun ownKeyFor(catalogKey: String) = OWN_PREFIX + catalogKey
    }

    private fun readSet(key: String): Set<String> =
        prefs.getStringSet(key, null)?.toSet() ?: emptySet()

    private fun writeSet(key: String, names: Set<String>) {
        // A copy is stored: SharedPreferences keeps a reference to the set.
        prefs.edit().putStringSet(key, names.toSet()).apply()
    }

    /** Providers blocked in every catalog. */
    fun globalBlocked(): Set<String> = readSet(GLOBAL_KEY)

    /** Providers blocked only in this catalog. */
    fun ownBlocked(): Set<String> = readSet(ownKeyFor(catalogKey))

    /** Union of both levels: what is actually excluded. */
    fun effectiveBlocked(): Set<String> = globalBlocked() + ownBlocked()

    /** True unless the provider is blocked in this catalog (globally or locally). */
    fun isAllowed(apiName: String?): Boolean =
        apiName == null || apiName !in effectiveBlocked()

    fun isGloballyBlocked(apiName: String): Boolean = apiName in globalBlocked()

    /** Current blocklist of the selected scope (global or this catalog). */
    fun blockedFor(global: Boolean): Set<String> =
        if (global) globalBlocked() else ownBlocked()

    fun setBlocked(names: Set<String>, global: Boolean) {
        if (global) writeSet(GLOBAL_KEY, names) else writeSet(ownKeyFor(catalogKey), names)
    }

    /**
     * Signature of the effective list, included in the provider search cache key
     * so a settings change does not return results cached with the old
     * selection.
     */
    fun signature(): Int =
        effectiveBlocked().sorted().joinToString("\u0000").hashCode()
}
