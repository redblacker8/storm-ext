package com.stormunblessed

import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.stormunblessed.settings.CatalogoSourceFilter
import com.stormunblessed.settings.CatalogoSourceFilterFragment
import com.stormunblessed.settings.findAppCompatActivity

@CloudstreamPlugin
class CatalogoGeneralProviderPlugin : Plugin() {
    override fun load(context: Context) {
        // The preferences file is shared by every catalog plugin so the global
        // blocklist ("Todos los catálogos") is really common to all of them.
        val prefs = context.getSharedPreferences(
            CatalogoSourceFilter.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val filter = CatalogoSourceFilter(prefs, CatalogoSourceFilter.KEY_GENERAL)
        val provider = CatalogoGeneralProvider(filter)

        registerMainAPI(provider)

        // Gear icon in the provider row -> "Fuentes permitidas" panel.
        openSettings = { ctx ->
            val activity = ctx.findAppCompatActivity()
            if (activity != null) {
                CatalogoSourceFilterFragment(filter, provider.name, provider.lang)
                    .show(activity.supportFragmentManager, "CatalogoSourceFilter")
            } else {
                Toast.makeText(ctx, "Abre los ajustes desde la app", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
