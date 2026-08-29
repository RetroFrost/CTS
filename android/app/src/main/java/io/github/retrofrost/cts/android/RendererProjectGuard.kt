package io.github.retrofrost.cts.android

data class RendererProjectCompatibility(
    val compatible: Boolean,
    val issues: List<String>,
) {
    fun message(rendererName: String): String = if (compatible) {
        "$rendererName is compatible with this project."
    } else {
        "$rendererName is source-locked and cannot be applied to this project: ${issues.joinToString()}."
    }
}

object RendererProjectGuard {
    private fun isSourceLocked(spec: RendererSpec): Boolean =
        RelationshipsPrecisionFrameRenderer.enabled(spec) && spec.precisionMode == "frame-exact"

    fun check(project: StudioProject, spec: RendererSpec): RendererProjectCompatibility {
        if (!isSourceLocked(spec)) return RendererProjectCompatibility(true, emptyList())

        val issues = mutableListOf<String>()
        if (project.width != spec.referenceWidth || project.height != spec.referenceHeight) {
            issues += "resolution is ${project.width}×${project.height}; requires ${spec.referenceWidth}×${spec.referenceHeight}"
        }
        if (project.fps != spec.referenceFps) {
            issues += "frame rate is ${project.fps} fps; requires ${spec.referenceFps} fps"
        }
        if (spec.canonicalCardCount > 0 && project.cards.size != spec.canonicalCardCount) {
            issues += "card count is ${project.cards.size}; requires ${spec.canonicalCardCount}"
        }
        if (!project.autoLength) {
            issues += "custom duration is enabled; canonical automatic duration is required"
        }
        return RendererProjectCompatibility(issues.isEmpty(), issues)
    }

    fun requireCompatible(project: StudioProject, spec: RendererSpec) {
        val result = check(project, spec)
        require(result.compatible) {
            result.message(spec.name) + " Use a compatible renderer or restore the canonical project settings."
        }
    }
}
