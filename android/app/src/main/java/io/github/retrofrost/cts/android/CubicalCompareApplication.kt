package io.github.retrofrost.cts.android

import android.app.Application

class CubicalCompareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RendererRuntime.active = RendererStore(this).active()
    }
}
