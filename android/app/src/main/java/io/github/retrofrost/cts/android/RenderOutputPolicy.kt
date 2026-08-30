package io.github.retrofrost.cts.android

/** Pure output policy shared by preview, metadata and export. */
internal object RenderOutputPolicy {
    fun resolve(project: StudioProject, spec: RendererSpec): StudioProject =
        if (spec.precisionMode == "frame-exact") {
            project.copy(
                width = spec.referenceWidth.coerceAtLeast(2),
                height = spec.referenceHeight.coerceAtLeast(2),
                fps = spec.referenceFps.coerceIn(1, 240),
            )
        } else {
            project.copy(
                width = project.width.coerceAtLeast(2),
                height = project.height.coerceAtLeast(2),
                fps = project.fps.coerceIn(1, 120),
            )
        }
}
