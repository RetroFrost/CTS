package io.github.retrofrost.cts.android.export

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceExportPolicyTest {
    private val fourGiB = 4L * 1024L * 1024L * 1024L
    private val twoGiB = 2L * 1024L * 1024L * 1024L

    @Test fun limitedMemoryPrefers720p30() {
        val result = DeviceExportPolicy.recommend(false, twoGiB) { _, _, _ -> true }
        assertEquals(1280, result.width)
        assertEquals(720, result.height)
        assertEquals(30, result.fps)
    }

    @Test fun lowRamFallsBackWhen720pIsUnsupported() {
        val result = DeviceExportPolicy.recommend(true, fourGiB) { width, _, _ -> width == 640 }
        assertEquals(640, result.width)
        assertEquals(360, result.height)
    }

    @Test fun modernDevicePrefers1080p60() {
        val result = DeviceExportPolicy.recommend(false, fourGiB) { _, _, _ -> true }
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertEquals(60, result.fps)
    }

    @Test fun modernDeviceFallsBackTo1080p30() {
        val result = DeviceExportPolicy.recommend(false, fourGiB) { _, _, fps -> fps == 30 }
        assertEquals(1920, result.width)
        assertEquals(30, result.fps)
    }

    @Test fun noHardwareTargetsFallsBackSafely() {
        val result = DeviceExportPolicy.recommend(false, 0L) { _, _, _ -> false }
        assertEquals(640, result.width)
        assertEquals(360, result.height)
    }
}
