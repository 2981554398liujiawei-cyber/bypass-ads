package app.bypassads.accessibility

/**
 * Window identity token (M2.2 P1-2): derived from the real
 * AccessibilityWindowInfo ids, never from layout fingerprints. Two different
 * windows with identical window counts / node bounds produce different tokens,
 * so the gate's WINDOW_CHANGED check actually detects window replacement.
 * Order-sensitive on purpose: any window set/order change rotates the token.
 */
object WindowToken {
    fun of(windowIds: List<Int>): Long = windowIds.fold(0L) { acc, id -> acc * 31 + id.toLong() }
}
