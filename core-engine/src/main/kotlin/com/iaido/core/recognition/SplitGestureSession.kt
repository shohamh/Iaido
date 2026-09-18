package com.iaido.core.recognition

import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint

data class SplitWordParts(
    val parts: List<String>,
    val paths: List<GesturePath>,
)

/** Collects concurrent partial gestures until the bounded grace window closes. */
class SplitGestureSession(private var graceWindowMs: Long = 350L) {
    private data class ActivePart(
        val pointerId: Int,
        val touchOrder: Int,
        val points: MutableList<GesturePoint>,
        var tapSuffix: String = "",
    )

    private data class CompletedPart(
        val touchOrder: Int,
        val path: GesturePath,
        val letters: String,
    )

    private val active = linkedMapOf<Int, ActivePart>()
    private val completed = mutableListOf<CompletedPart>()
    private var nextTouchOrder = 0
    private var graceDeadlineMs: Long? = null

    fun begin(pointerId: Int, point: GesturePoint, atMs: Long) {
        expireIfLate(atMs)
        if (pointerId in active) return
        graceDeadlineMs = null
        active[pointerId] = ActivePart(pointerId, nextTouchOrder++, mutableListOf(point))
    }

    fun move(pointerId: Int, point: GesturePoint) {
        active[pointerId]?.points?.add(point)
    }

    fun end(pointerId: Int, atMs: Long, letters: String) {
        val part = active.remove(pointerId) ?: return
        completed += CompletedPart(
            touchOrder = part.touchOrder,
            path = GesturePath(part.points.toList()),
            letters = letters + part.tapSuffix,
        )
        if (active.isEmpty()) graceDeadlineMs = atMs + graceWindowMs
    }

    fun tap(pointerId: Int, letter: Char, atMs: Long): Boolean {
        expireIfLate(atMs)
        if (active.isEmpty() && graceDeadlineMs == null) return false
        active.remove(pointerId)?.let { part ->
            completed += CompletedPart(
                touchOrder = part.touchOrder,
                path = GesturePath(part.points.toList()),
                letters = letter + part.tapSuffix,
            )
            if (active.isEmpty()) graceDeadlineMs = atMs + graceWindowMs
            return true
        }
        active.values.lastOrNull()?.let {
            it.tapSuffix += letter
            return true
        }
        val last = completed.maxByOrNull { it.touchOrder } ?: return false
        completed[completed.indexOf(last)] = last.copy(letters = last.letters + letter)
        return true
    }

    fun poll(atMs: Long): SplitWordParts? {
        val deadline = graceDeadlineMs ?: return null
        if (active.isNotEmpty() || atMs < deadline) return null
        val result = completed.sortedBy { it.touchOrder }
        clear()
        return SplitWordParts(result.map { it.letters }, result.map { it.path })
    }

    /** True while another pointer or the grace window can still produce parts. */
    fun isPending(): Boolean = active.isNotEmpty() || graceDeadlineMs != null

    fun cancel() {
        clear()
    }

    fun setGraceWindowMs(value: Long) {
        require(value >= 0L) { "Grace window must not be negative" }
        graceWindowMs = value
    }

    private fun expireIfLate(atMs: Long) {
        if (graceDeadlineMs != null && atMs >= graceDeadlineMs!! && active.isEmpty()) clear()
    }

    private fun clear() {
        active.clear()
        completed.clear()
        graceDeadlineMs = null
        nextTouchOrder = 0
    }
}
