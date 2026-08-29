package io.github.retrofrost.cts.android

import android.app.Application

class CubicalCompareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = RendererStore(this)
        val selected = store.active()
        val project = ProjectAutosave.load(this)
        RendererRuntime.active = if (
            project != null && !RendererProjectGuard.check(project, selected).compatible
        ) {
            // Keep the exact renderer installed, but never let an incompatible
            // autosaved project start up as a renderer/project hybrid.
            store.reset()
        } else {
            selected
        }
    }
}
