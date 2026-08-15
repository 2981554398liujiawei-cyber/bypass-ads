package app.lttnext.core.model

data class UiNodeSnapshot(
    val index: Int,
    val parentIndex: Int?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val bounds: IntRect,
    val clickable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val depth: Int,
    val nearestClickableAncestorBounds: IntRect?,
)

data class UiSnapshot(
    val packageName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val capturedAtElapsedMs: Long,
    val windowCount: Int,
    val nodes: List<UiNodeSnapshot>,
)
