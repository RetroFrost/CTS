from pathlib import Path

p = Path('android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt')
s = p.read_text()

old = '''        bodyOrder.forEach { index ->
            drawCardBody(canvas, project, project.cards[index], positions.getValue(index), spec)
        }
'''
new = '''        bodyOrder.forEach { index ->
            val x = positions.getValue(index)
            val clip = openingClip(project, spec, index, frame)
            if (clip != null) {
                if (clip.second > clip.first) {
                    canvas.save()
                    canvas.clipRect(clip.first, 0f, clip.second, REFERENCE_HEIGHT.toFloat())
                    drawCardBody(canvas, project, project.cards[index], x, spec)
                    canvas.restore()
                }
            } else {
                drawCardBody(canvas, project, project.cards[index], x, spec)
            }
        }
'''
assert old in s, 'body draw loop not found'
s = s.replace(old, new, 1)

old = '''        sortedIndices.forEach { index ->
            if (project.cards[index].imageLayer.equals("front", ignoreCase = true)) {
                drawFrontArtwork(canvas, project.cards[index], positions.getValue(index), spec)
            }
        }
    }

    private fun positionsForFrame'''
new = '''        sortedIndices.forEach { index ->
            if (project.cards[index].imageLayer.equals("front", ignoreCase = true)) {
                val x = positions.getValue(index)
                val clip = openingClip(project, spec, index, frame)
                if (clip != null) {
                    if (clip.second > clip.first) {
                        canvas.save()
                        canvas.clipRect(clip.first, 0f, clip.second, REFERENCE_HEIGHT.toFloat())
                        drawFrontArtwork(canvas, project.cards[index], x, spec)
                        canvas.restore()
                    }
                } else {
                    drawFrontArtwork(canvas, project.cards[index], x, spec)
                }
            }
        }
    }

    private fun openingClip(
        project: StudioProject,
        spec: RendererSpec,
        index: Int,
        frame: Int,
    ): Pair<Float, Float>? {
        if (index !in 0..3 || frame >= spec.continuousStartFrame) return null
        val local = frame - RibbonTimeline.cardStartFrame(project, spec, index)
        val left = motionTrack(spec, "ribbon.open.$index.clip.left", local)
        val right = motionTrack(spec, "ribbon.open.$index.clip.right", local)
        if (left == null && right == null) return null
        val slotLeft = index * spec.slotPitch
        return (left ?: slotLeft) to (right ?: slotLeft + spec.slotPitch)
    }

    private fun positionsForFrame'''
assert old in s, 'front draw loop marker not found'
s = s.replace(old, new, 1)

old = '''        val local = frame - RibbonTimeline.cardStartFrame(project, spec, active)
        val exactX = motionTrack(spec, "ribbon.open.$active.card.x", local)
        val progress = bodyProgress(spec, local)
        result[active] = exactX ?: if (active == 0) {
            lerp(-spec.slotPitch, 0f, progress)
        } else {
            lerp((active - 1) * spec.slotPitch, active * spec.slotPitch, progress)
        }
'''
new = '''        val local = frame - RibbonTimeline.cardStartFrame(project, spec, active)
        val hasExactClip = spec.track("ribbon.open.$active.clip.left", local) != null ||
            spec.track("ribbon.open.$active.clip.right", local) != null
        val exactX = motionTrack(spec, "ribbon.open.$active.card.x", local)
        val progress = bodyProgress(spec, local)
        result[active] = if (hasExactClip) {
            active * spec.slotPitch
        } else exactX ?: if (active == 0) {
            lerp(-spec.slotPitch, 0f, progress)
        } else {
            lerp((active - 1) * spec.slotPitch, active * spec.slotPitch, progress)
        }
'''
assert old in s, 'opening position block not found'
s = s.replace(old, new, 1)

old = '''        val segment = (frame - spec.continuousStartFrame) / SCROLL_TRACK_SIZE
        if (segment !in 0..2) return null
        return motionTrack(spec, "ribbon.scroll.$segment", frame)
'''
new = '''        val segment = (frame - spec.continuousStartFrame) / SCROLL_TRACK_SIZE
        return motionTrack(spec, "ribbon.scroll.$segment", frame)
'''
assert old in s, 'scroll segment limit not found'
s = s.replace(old, new, 1)

p.write_text(s)
