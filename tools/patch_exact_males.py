from pathlib import Path

p = Path('android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt')
s = p.read_text()
old = '                            xInCards = index - 1f + slide,'
new = '''                            xInCards = if (lockedMales) {
                                val sourceFrame = (modelTime * MALES_REFERENCE_FPS).toInt()
                                ExactReferenceFrames.malesOpeningCardX(sourceFrame, index)?.div(480f)
                                    ?: (index - 1f + slide)
                            } else index - 1f + slide,'''
if old in s:
    s = s.replace(old, new, 1)

a = s.index('    private fun malesConveyorShift(')
b = s.index('    private fun malesFrameForShift(', a)
s = s[:a] + '''    private fun malesConveyorShift(sourceFrame: Int, maximumShift: Float): Float {
        if (maximumShift <= 0f || sourceFrame < ExactReferenceFrames.MALES_CONVEYOR_START) return 0f
        val cardX = ExactReferenceFrames.malesConveyorCardX(sourceFrame, 0) ?: return 0f
        return (-cardX / 480f).coerceIn(0f, maximumShift)
    }

''' + s[b:]

a = s.index('    private fun malesCardStartFrame(')
b = s.index('    private fun malesReferenceFrameCount(', a)
s = s[:a] + '''    private fun malesCardStartFrame(index: Int): Float =
        ExactReferenceFrames.malesCardStartFrame(index).toFloat()

''' + s[b:]

a = s.index('    private fun malesMeasuredBadgeAffine(')
b = s.index('    private fun malesBodyProgress(', a)
s = s[:a] + '''    private fun malesMeasuredBadgeAffine(
        index: Int,
        age: Float,
        sourceFrame: Int,
        cardCount: Int,
        initialCount: Int,
    ): BadgeAffine {
        if (index < initialCount) {
            ExactReferenceFrames.malesOpeningBadgeAffine(sourceFrame, index)?.let { return it }
        } else {
            ExactReferenceFrames.malesPostBadgeAffine(sourceFrame, index)?.let { return it }
        }
        if (age < MALES_BADGE_ENTRY_END) return BadgeAffine.Identity
        val scale = malesStageScale(index, sourceFrame, cardCount)
        val cx = 243.5f
        val cy = 203.5f
        return BadgeAffine(scale, 0f, 0f, scale, cx * (1f - scale), cy * (1f - scale))
    }

''' + s[b:]
p.write_text(s)
