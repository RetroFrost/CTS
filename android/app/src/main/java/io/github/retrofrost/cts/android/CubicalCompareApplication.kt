package io.github.retrofrost.cts.android

import android.app.Application

class CubicalCompareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep the user's selected renderer active across projects. Project/card
        // differences are reported by the UI as Modified; they do not deactivate
        // or replace a renderer behind the user's back.
        RendererRuntime.active = RendererStore(this).active()
    }
}
