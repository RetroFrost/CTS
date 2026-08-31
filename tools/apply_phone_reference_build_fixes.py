#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
RIBBON = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


bundle = BUNDLE.read_text()
bundle = replace_once(
    bundle,
    '    const val APP_VERSION = "2.0.8"',
    '    val APP_VERSION: String get() = BuildConfig.VERSION_NAME.substringBefore(\'-\')',
    "runtime app version",
)
# Geometry measured from the supplied 1920x1080/60 phone reference: the body
# starts at x=10, is 470 px wide, and repeats every 477 px at the settled four-card frame.
bundle = replace_once(bundle, '    val slotPitch: Float = 476f,', '    val slotPitch: Float = 477f,', "slot pitch")
bundle = replace_once(bundle, '    val bodyInset: Float = 9f,', '    val bodyInset: Float = 10f,', "body inset")
bundle = replace_once(bundle, '    val bodyWidth: Float = 471f,', '    val bodyWidth: Float = 470f,', "body width")
BUNDLE.write_text(bundle)

ribbon = RIBBON.read_text()
ribbon = replace_once(
    ribbon,
    '            val scroll = exact ?: ((frame - spec.continuousStartFrame).toFloat() / step * spec.slotPitch)',
    '            val scroll = exact ?: fallbackContinuousScroll(spec, frame, step)',
    "continuous scroll fallback",
)

old_exact_and_body = '''    private fun exactScroll(spec: RendererSpec, frame: Int): Float? {
        if (frame < spec.continuousStartFrame) return null
        val segment = (frame - spec.continuousStartFrame) / SCROLL_TRACK_SIZE
        return motionTrack(spec, "ribbon.scroll.$segment", frame)
    }

    private fun bodyProgress(spec: RendererSpec, localFrame: Int): Float {
        motionTrack(spec, "ribbon.body.progress", localFrame)?.let { return it.coerceIn(0f, 1f) }
        val p = (localFrame.toFloat() / spec.bodySlideFrames.coerceAtLeast(1)).coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }
'''
new_exact_and_body = '''    private fun exactScroll(spec: RendererSpec, frame: Int): Float? {
        if (frame < spec.continuousStartFrame) return null
        val segment = (frame - spec.continuousStartFrame) / SCROLL_TRACK_SIZE
        return motionTrack(spec, "ribbon.scroll.$segment", frame)
    }

    private fun fallbackContinuousScroll(spec: RendererSpec, frame: Int, step: Int): Float {
        val local = (frame - spec.continuousStartFrame).coerceAtLeast(0)
        val linear = local.toFloat() / step.coerceAtLeast(1) * spec.slotPitch
        if (step != spec.continuousStepFrames) return linear
        // Measured from description-band separators in the supplied phone video.
        // The conveyor has a one-time phase pull during the opening->continuous hand-off,
        // then resumes constant velocity. Do not repeat this curve on every card.
        val correction = sampleScalar(
            arrayOf(
                0f to 0f, 10f to -17f, 20f to -24f, 30f to -18f,
                40f to -11f, 50f to -1f, 60f to 12f, 70f to 28f,
                80f to 46f, 90f to 62f, 100f to 69f, 120f to 72f,
            ),
            local.toFloat().coerceAtMost(120f),
        )
        return linear + correction
    }

    private fun bodyProgress(spec: RendererSpec, localFrame: Int): Float {
        motionTrack(spec, "ribbon.body.progress", localFrame)?.let { return it.coerceIn(0f, 1f) }
        // Frame measurements from the first card's right edge in the supplied
        // 60 FPS reference. Linear interpolation between dense measurements avoids
        // adding a generic smoothstep/easing curve that is not present in the source.
        return sampleScalar(
            arrayOf(
                0f to 0f, 5f to 0f, 10f to 0.06918f, 15f to 0.23061f,
                20f to 0.45912f, 25f to 0.61635f, 30f to 0.71908f,
                35f to 0.79245f, 40f to 0.84696f, 45f to 0.88889f,
                50f to 0.92034f, 55f to 0.94549f, 60f to 0.96226f,
                65f to 0.97694f, 70f to 0.98742f, 75f to 0.99371f,
                80f to 1f,
            ),
            localFrame.toFloat(),
        ).coerceIn(0f, 1f)
    }

    private fun sampleScalar(keys: Array<Pair<Float, Float>>, value: Float): Float {
        if (value <= keys.first().first) return keys.first().second
        if (value >= keys.last().first) return keys.last().second
        for (index in 1 until keys.size) {
            val right = keys[index]
            if (value <= right.first) {
                val left = keys[index - 1]
                val p = (value - left.first) / (right.first - left.first).coerceAtLeast(0.0001f)
                return lerp(left.second, right.second, p)
            }
        }
        return keys.last().second
    }
'''
ribbon = replace_once(ribbon, old_exact_and_body, new_exact_and_body, "measured body/scroll")

old_open = '''            val values = floatArrayOf(
                motionTrack(spec, "$prefix.m00", local) ?: 1f,
                motionTrack(spec, "$prefix.m01", local) ?: 0f,
                motionTrack(spec, "$prefix.tx", local) ?: 0f,
                motionTrack(spec, "$prefix.m10", local) ?: 0f,
                motionTrack(spec, "$prefix.m11", local) ?: 1f,
                motionTrack(spec, "$prefix.ty", local) ?: 0f,
                0f, 0f, 1f,
            )
'''
new_open = '''            val fallbackAffine = openingBadgeAffine(local)
            val values = floatArrayOf(
                motionTrack(spec, "$prefix.m00", local) ?: fallbackAffine[0],
                motionTrack(spec, "$prefix.m01", local) ?: fallbackAffine[1],
                motionTrack(spec, "$prefix.tx", local) ?: fallbackAffine[2],
                motionTrack(spec, "$prefix.m10", local) ?: fallbackAffine[3],
                motionTrack(spec, "$prefix.m11", local) ?: fallbackAffine[4],
                motionTrack(spec, "$prefix.ty", local) ?: fallbackAffine[5],
                0f, 0f, 1f,
            )
'''
ribbon = replace_once(ribbon, old_open, new_open, "opening badge affine")
ribbon = replace_once(
    ribbon,
    '            matrix.setTranslate(0f, motionTrack(spec, "ribbon.card.$index.badge.y", local) ?: motionTrack(spec, "ribbon.later.badge.y", local) ?: 0f)',
    '            matrix.setTranslate(0f, motionTrack(spec, "ribbon.card.$index.badge.y", local) ?: motionTrack(spec, "ribbon.later.badge.y", local) ?: laterBadgeYOffset(local))',
    "later badge fall",
)
# Source hierarchy is active -> 272/298 -> 248/298; the old 0.90/0.75 fallback
# made older badges visibly too small.
ribbon = replace_once(ribbon, '            scale = lerp(1f, 0.90f, p)', '            scale = lerp(1f, 272f / 298f, p)', "medium badge scale")
ribbon = replace_once(ribbon, '            if (p > 0f) scale = lerp(0.90f, 0.75f, p)', '            if (p > 0f) scale = lerp(272f / 298f, 248f / 298f, p)', "small badge scale")

marker = '''    private fun badgeDeemphasisScale(project: StudioProject, index: Int, globalFrame: Int, spec: RendererSpec): Float {'''
helpers = '''    private fun openingBadgeAffine(localFrame: Int): FloatArray {
        // Dense contour measurements from the WatchData-style phone reference.
        // Values are Android Matrix order: m00,m01,tx,m10,m11,ty.
        val keys = arrayOf(
            floatArrayOf(35f, 0.493398f, -0.085460f, -150.997648f, -0.331527f, 1.161492f, -39.870887f),
            floatArrayOf(40f, 0.592169f, -0.078765f, -125.999331f, -0.283855f, 1.188568f, -19.155194f),
            floatArrayOf(44f, 0.653847f, -0.078786f, -105.417801f, -0.293365f, 1.172057f, -5.568381f),
            floatArrayOf(48f, 0.696013f, -0.090493f, -82.436779f, -0.273790f, 1.202435f, -19.898183f),
            floatArrayOf(52f, 0.721844f, -0.076480f, -60.473294f, -0.273350f, 1.200237f, -18.705733f),
            floatArrayOf(56f, 0.729938f, -0.029255f, -45.263002f, -0.225309f, 1.111702f, -10.894799f),
            floatArrayOf(60f, 0.774691f, -0.031915f, -38.958629f, -0.189815f, 1.114362f, -14.476950f),
            floatArrayOf(64f, 0.817901f, -0.031915f, -34.569740f, -0.168210f, 1.114362f, -17.032506f),
            floatArrayOf(68f, 0.859568f, -0.039894f, -30.989953f, -0.121914f, 1.087766f, -17.599882f),
            floatArrayOf(72f, 0.898148f, -0.037234f, -29.794326f, -0.114198f, 1.069149f, -14.969267f),
            floatArrayOf(76f, 0.922840f, -0.026596f, -28.178487f, -0.101852f, 1.053191f, -11.698582f),
            floatArrayOf(80f, 0.945988f, -0.029255f, -23.818558f, -0.067901f, 1.058511f, -15.196217f),
            floatArrayOf(84f, 0.964506f, -0.018617f, -23.258274f, -0.064815f, 1.042553f, -10.258865f),
            floatArrayOf(88f, 0.979938f, -0.023936f, -19.316194f, -0.038580f, 1.039894f, -13.121158f),
            floatArrayOf(92f, 0.987654f, -0.021277f, -16.898345f, -0.023148f, 1.029255f, -10.625887f),
            floatArrayOf(96f, 1.001543f, -0.013298f, -15.978132f, -0.021605f, 1.021277f, -8.657210f),
            floatArrayOf(100f, 1.010802f, -0.013298f, -14.144799f, -0.006173f, 1.005319f, -6.608747f),
            floatArrayOf(104f, 1.013889f, -0.007979f, -11.420213f, -0.006173f, 1.010638f, -6.661939f),
            floatArrayOf(108f, 1.010802f, 0.002660f, -8.304374f, -0.015432f, 1.015957f, -3.548463f),
            floatArrayOf(112f, 1.021605f, -0.010638f, -4.449173f, 0.009259f, 1.005319f, -6.219858f),
            floatArrayOf(116f, 1.024691f, -0.010638f, -2.171395f, 0.020062f, 0.986702f, -1.311466f),
            floatArrayOf(120f, 1f, 0f, 0f, 0f, 1f, 0f),
        )
        if (localFrame <= keys.first()[0]) return keys.first().copyOfRange(1, 7)
        if (localFrame >= keys.last()[0]) return keys.last().copyOfRange(1, 7)
        for (index in 1 until keys.size) {
            val right = keys[index]
            if (localFrame <= right[0]) {
                val left = keys[index - 1]
                val p = (localFrame - left[0]) / (right[0] - left[0]).coerceAtLeast(1f)
                return FloatArray(6) { component -> lerp(left[component + 1], right[component + 1], p) }
            }
        }
        return keys.last().copyOfRange(1, 7)
    }

    private fun laterBadgeYOffset(localFrame: Int): Float {
        val keys = arrayOf(
            122f to -430f, 142f to -410f, 151f to -386f, 152f to -381f,
            154f to -381f, 156f to -341f, 158f to -321f, 160f to -300f,
            162f to -279f, 164f to -266f, 166f to -246f, 168f to -226f,
            170f to -206f, 172f to -187f, 174f to -175f, 176f to -156f,
            178f to -138f, 180f to -121f, 182f to -105f, 184f to -94f,
            186f to -80f, 188f to -66f, 190f to -53f, 192f to -41f,
            194f to -34f, 196f to -25f, 198f to -17f, 200f to -10f,
            202f to -5f, 204f to -2f, 206f to 0f,
        )
        return sampleScalar(keys, localFrame.toFloat())
    }

'''
if helpers not in ribbon:
    if ribbon.count(marker) != 1:
        raise SystemExit("badge helper insertion marker changed")
    ribbon = ribbon.replace(marker, helpers + marker, 1)

RIBBON.write_text(ribbon)
print("Applied runtime-version, direct phone geometry, and measured Ribbon motion fixes")
