package io.github.retrofrost.cts.android

import android.app.Application
import android.content.Context

class CubicalCompareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
