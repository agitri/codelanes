package dev.codelanes.layout

/** Keyboard moves between blocks in reading order: lane by lane (left to right), top to bottom within a lane. */
object BlockNavigation {
    /** The block after (or before) [from] that may take focus ([allowed]), or null at the end. */
    fun next(rects: Map<String, Rect>, from: String, down: Boolean, allowed: Set<String>): String? {
        val order = rects.entries.sortedWith(compareBy({ it.value.x }, { it.value.y })).map { it.key }
        val index = order.indexOf(from)
        if (index < 0) return null
        val candidates = if (down) order.drop(index + 1) else order.take(index).reversed()
        return candidates.firstOrNull { it in allowed }
    }
}
