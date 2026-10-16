package com.retrofrost.cubicalcompare.v5

import android.content.Context
import java.io.File
import java.util.function.IntConsumer

/** Java entry point for the exact-frame Renderer3 encoder and timeline duration. */
object Renderer3ExportInterop {
    @JvmStatic fun encode(ctx:Context,project:StudioModel.Project,output:File,
        progress:IntConsumer, cancelled:java.util.function.BooleanSupplier)=
        Renderer3VideoEncoder.encode(ctx,project,output,{progress.accept(it)},{cancelled.getAsBoolean()})

    @JvmStatic fun durationMillis(ctx:Context,project:StudioModel.Project):Long {
        val selected=Renderer3Bridge.resolve(ctx,project)
            ?:return NativeEngine.totalFrames(StudioModel.timelineJson(project))*1000L/project.Fps.coerceAtLeast(1)
        return selected.frameCountForCards(project.Cards.size).toLong()*1000L/
            selected.fps.coerceAtLeast(1)
    }
}
