package app.lttnext.core.model

data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun areaRatio(screenWidth: Int, screenHeight: Int): Double {
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        return if (screenArea <= 0L) 1.0 else area.toDouble() / screenArea.toDouble()
    }
}
