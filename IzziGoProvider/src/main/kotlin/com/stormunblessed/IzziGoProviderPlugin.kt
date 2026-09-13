package com.stormunblessed

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.stormunblessed.settings.LoginFragment

@CloudstreamPlugin
class IzziGoProviderPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("IzziGo", Context.MODE_PRIVATE)
        val api = IzziGoApi(prefs)
        registerMainAPI(IzziGoProvider(api))
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            LoginFragment(api).show(activity.supportFragmentManager, "IzziGoLogin")
        }
    }
}
