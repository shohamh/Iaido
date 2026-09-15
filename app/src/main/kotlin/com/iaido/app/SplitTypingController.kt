package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.SplitGestureSession
import com.iaido.core.recognition.SplitWordMerger

class SplitTypingController(
    private val dictionary: () -> List<WordEntry>,
    private val commitText: (String) -> Unit,
    private val session: SplitGestureSession = SplitGestureSession(),
    private val merger: SplitWordMerger = SplitWordMerger(),
) {
    fun begin(pointerId: Int, point: GesturePoint, atMs: Long) = session.begin(pointerId, point, atMs)

    fun move(pointerId: Int, point: GesturePoint) = session.move(pointerId, point)

    fun end(pointerId: Int, atMs: Long, letters: String) = session.end(pointerId, atMs, letters)

    fun finish(pointerId: Int, path: GesturePath, layout: KeyboardLayout, atMs: Long) {
        val letters = path.points
            .mapNotNull { point ->
                layout.keys.minByOrNull { key ->
                    val dx = point.x - key.x
                    val dy = point.y - key.y
                    dx * dx + dy * dy
                }?.letter
            }
            .fold(StringBuilder()) { result, letter ->
                if (result.lastOrNull() != letter) result.append(letter)
                result
            }
            .toString()
        if (path.points.size < 2 && letters.length == 1) session.tap(pointerId, letters.single(), atMs)
        else session.end(pointerId, atMs, letters)
    }

    fun tap(pointerId: Int, letter: Char, atMs: Long): Boolean = session.tap(pointerId, letter, atMs)

    fun poll(atMs: Long): String? {
        val parts = pollParts(atMs) ?: return null
        val candidates = merger.mergeParts(parts.parts, dictionary())
        val match = candidates.maxByOrNull { it.frequency } ?: return null
        commitText(match.word)
        return match.word
    }

    fun pollParts(atMs: Long) = session.poll(atMs)

    fun isPending() = session.isPending()

    fun cancel() = session.cancel()
}
