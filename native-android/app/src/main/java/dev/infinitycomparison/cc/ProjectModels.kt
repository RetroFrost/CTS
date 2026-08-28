package dev.thedataguys.cc

data class CompareItem(
    val rank: Int,
    val title: String,
    val subtitle: String,
    val value: String,
    val note: String = ""
)

data class CompareProject(
    val title: String,
    val subtitle: String,
    val items: List<CompareItem>,
    val fps: Int = 30,
    val seconds: Int = 18,
    val width: Int = 1080,
    val height: Int = 1920
) {
    companion object {
        fun demo(): CompareProject = CompareProject(
            title = "Worst Things To Hear",
            subtitle = "Renderer test project",
            items = listOf(
                CompareItem(1, "Your storage is full", "Right before exporting", "96%"),
                CompareItem(2, "Preview failed", "When the app looked finished", "ouch"),
                CompareItem(3, "One more tiny fix", "Usually means five more bugs", "danger"),
                CompareItem(4, "The last badge moved", "Now it enters from the right", "fixed"),
                CompareItem(5, "Renderer imported", "No APK rebuild required", "new"),
                CompareItem(6, "Timing verified", "Millisecond keyframes active", "1:1"),
                CompareItem(7, "GPU export ready", "MediaCodec + EGL", "fast")
            )
        )
    }
}
