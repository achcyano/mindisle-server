package me.hztcm.mindisle.ai.service

internal class AiDeltaOptionFilter(
    private val startMarker: String = OPTIONS_START_MARKER,
    private val endMarker: String = OPTIONS_END_MARKER
) {
    private val buffer = StringBuilder()
    private var insideOptionsBlock = false

    fun accept(chunk: String): String {
        if (chunk.isEmpty()) {
            return ""
        }
        buffer.append(chunk)
        val visible = StringBuilder()

        while (buffer.isNotEmpty()) {
            val progressed = if (insideOptionsBlock) {
                processHiddenSegment()
            } else {
                processVisibleSegment(visible)
            }
            if (!progressed) {
                break
            }
        }

        return visible.toString()
    }

    fun flushRemainder(): String {
        if (insideOptionsBlock || buffer.isEmpty()) {
            buffer.setLength(0)
            return ""
        }
        val tail = buffer.toString()
        buffer.setLength(0)
        return tail
    }

    private fun processHiddenSegment(): Boolean {
        val endIdx = buffer.indexOf(endMarker)
        if (endIdx >= 0) {
            buffer.delete(0, endIdx + endMarker.length)
            insideOptionsBlock = false
            return true
        }
        keepSuffixForBoundary(endMarker.length)
        return false
    }

    private fun processVisibleSegment(visible: StringBuilder): Boolean {
        val startIdx = buffer.indexOf(startMarker)
        if (startIdx >= 0) {
            if (startIdx > 0) {
                visible.append(buffer, 0, startIdx)
            }
            buffer.delete(0, startIdx + startMarker.length)
            insideOptionsBlock = true
            return true
        }
        emitStableVisibleTail(visible, startMarker.length)
        return false
    }

    private fun keepSuffixForBoundary(markerLength: Int) {
        val keep = minOf(buffer.length, markerLength - 1)
        val drop = buffer.length - keep
        if (drop > 0) {
            buffer.delete(0, drop)
        }
    }

    private fun emitStableVisibleTail(visible: StringBuilder, markerLength: Int) {
        // Keep marker-length tail to avoid leaking partial marker across chunk boundaries.
        val keep = minOf(buffer.length, markerLength - 1)
        val emit = buffer.length - keep
        if (emit > 0) {
            visible.append(buffer, 0, emit)
            buffer.delete(0, emit)
        }
    }
}
