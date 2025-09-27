package com.donkey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DonkeyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Donkey(context))
    }
}
