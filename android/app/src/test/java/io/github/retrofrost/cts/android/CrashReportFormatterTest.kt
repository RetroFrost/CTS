package dev.infinitycomparison.cc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatterTest {
    @Test fun reportContainsUsefulContextWithoutProjectPayloads() {
        val report = CrashReportFormatter.format(
            CrashReportPayload(
                timestamp = "2026-08-24T22:00:00Z",
                reason = "Uncaught java.lang.IllegalStateException",
                versionName = "2.0.7",
                versionCode = 20007,
                device = "Samsung SM-S918B",
                androidVersion = "16 (SDK 36)",
                processId = 207,
                threadName = "DefaultDispatcher-worker-1",
                memory = "128 MiB used / 512 MiB maximum heap",
                lastState = "Export active: GPU rendering + encoding",
                exception = "java.lang.IllegalStateException: encoder stopped",
                recentEvents = listOf("Export started", "Export stage: Preparing GPU"),
            ),
        )

        assertTrue(report.contains("Cubical Compare automatic crash report"))
        assertTrue(report.contains("App: 2.0.7 (20007)"))
        assertTrue(report.contains("Samsung SM-S918B"))
        assertTrue(report.contains("GPU rendering + encoding"))
        assertTrue(report.contains("IllegalStateException: encoder stopped"))
        assertTrue(report.contains("Export stage: Preparing GPU"))
        assertFalse(report.contains("projectJson"))
    }
}
