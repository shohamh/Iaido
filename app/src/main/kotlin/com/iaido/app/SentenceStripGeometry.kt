package com.iaido.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class StripRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float = centerY): Boolean = x >= left && x <= right && y >= top && y <= bottom

    fun union(other: StripRect) = StripRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )
}

internal data class SentenceStripMeasuredWord(
    val id: String,
    val start: Int,
    val endExclusive: Int,
    val laneWidthPx: Float,
    val glyphWidthPx: Float,
    val baselinePx: Float,
    /** Physical x coordinates, relative to the glyph's left edge, from TextLayoutResult. */
    val cursorAnchorsPx: List<Float>,
)

internal data class SentenceStripWordGeometry(
    val id: String,
    val start: Int,
    val endExclusive: Int,
    val laneBounds: StripRect,
    val glyphBounds: StripRect,
    val hitBounds: StripRect,
    val baselinePx: Float,
    val cursorAnchorsPx: List<Float>,
)

/** One measured coordinate model shared by drawing, word hit testing, and gesture thresholds. */
internal data class SentenceStripGeometry(
    val words: List<SentenceStripWordGeometry>,
    val viewportWidthPx: Float,
    val contentWidthPx: Float,
    val addedGapPx: Float,
    val focusRunwayWidthPx: Float,
    val isRtl: Boolean,
) {
    /** Return the nearest lane whose expanded target contains x, resolving any overlap by center. */
    fun wordAt(x: Float): Int? = words.indices
        .filter { words[it].hitBounds.contains(x) }
        .minByOrNull { abs(words[it].glyphBounds.centerX - x) }

    fun joinUnion(firstIndex: Int, secondIndex: Int): StripRect {
        require(firstIndex in words.indices && secondIndex in words.indices)
        val lower = min(firstIndex, secondIndex)
        val upper = max(firstIndex, secondIndex)
        return words.subList(lower, upper + 1).map(SentenceStripWordGeometry::laneBounds)
            .reduce(StripRect::union)
    }

    /**
     * A vertical swipe becomes deletion only after it leaves its source lane. The source word is
     * then selected; another word joins the range only once its glyph midpoint is crossed. Moving
     * back into the source hit target returns null, restoring alternative selection.
     */
    fun deletionWordRange(originIndex: Int, x: Float): IntRange? {
        require(originIndex in words.indices)
        val origin = words[originIndex]
        if (origin.hitBounds.contains(x)) return null
        val movingRight = x > origin.glyphBounds.centerX
        val adjacent = words.indices
            .filter { it != originIndex }
            .map { it to words[it] }
            .filter { (_, word) ->
                if (movingRight) word.glyphBounds.centerX > origin.glyphBounds.centerX
                else word.glyphBounds.centerX < origin.glyphBounds.centerX
            }
            .minByOrNull { (_, word) -> abs(word.glyphBounds.centerX - origin.glyphBounds.centerX) }
            ?: return null
        val hasEnteredAdjacentWord = if (movingRight) {
            x >= adjacent.second.hitBounds.left
        } else {
            x <= adjacent.second.hitBounds.right
        }
        if (!hasEnteredAdjacentWord) return null
        val crossed = words.indices.filter { index ->
            val center = words[index].glyphBounds.centerX
            index == originIndex || if (movingRight) {
                center > origin.glyphBounds.centerX && center <= x
            } else {
                center < origin.glyphBounds.centerX && center >= x
            }
        }
        if (crossed.size <= 1) return originIndex..originIndex
        return crossed.minOrNull()!!..crossed.maxOrNull()!!
    }

    /** Convert measured glyph-relative insertion anchors to the editor's UTF-16 offset. */
    fun cursorOffsetAt(wordIndex: Int, x: Float): Int? {
        val word = words.getOrNull(wordIndex) ?: return null
        if (word.cursorAnchorsPx.isEmpty()) return null
        val relativeX = x - word.glyphBounds.left
        val anchor = word.cursorAnchorsPx.indices.minByOrNull { index ->
            abs(word.cursorAnchorsPx[index] - relativeX)
        } ?: return null
        return (word.start + anchor).coerceIn(word.start, word.endExclusive)
    }

    /** Prefer a centered focus, clamping naturally at the start and end of the content. */
    fun focusScrollOffset(wordIndex: Int): Float {
        val word = words.getOrNull(wordIndex) ?: return 0f
        return (word.glyphBounds.centerX - viewportWidthPx / 2f)
            .coerceIn(0f, max(0f, contentWidthPx - viewportWidthPx))
    }

    /** Physical content x for the editor caret, using the measured text layout anchors. */
    fun cursorContentX(wordIndex: Int, cursorPosition: Int): Float? {
        val word = words.getOrNull(wordIndex) ?: return null
        if (word.cursorAnchorsPx.isEmpty()) return null
        val anchorIndex = (cursorPosition - word.start).coerceIn(0, word.cursorAnchorsPx.lastIndex)
        val anchor = word.cursorAnchorsPx.getOrNull(anchorIndex) ?: return null
        return word.glyphBounds.left + anchor
    }

    companion object {
        const val WORD_GAP_CSS_PX = 4f

        fun create(
            words: List<SentenceStripMeasuredWord>,
            gapWidthsPx: List<Float>,
            viewportWidthPx: Float,
            isRtl: Boolean = false,
            addedGapPx: Float = WORD_GAP_CSS_PX,
            trailingContentWidthPx: Float = 0f,
        ): SentenceStripGeometry {
            require(gapWidthsPx.size == (words.size - 1).coerceAtLeast(0))
            require(words.all { it.laneWidthPx >= 0f && it.glyphWidthPx >= 0f })
            require(gapWidthsPx.all { it >= 0f })
            require(addedGapPx >= 0f)
            require(trailingContentWidthPx >= 0f)

            val measuredContentWidth = words.fold(0f) { total, word -> total + word.laneWidthPx } +
                gapWidthsPx.fold(0f, Float::plus) + addedGapPx * gapWidthsPx.size + trailingContentWidthPx
            val focusRunwayWidth = if (words.isNotEmpty() && measuredContentWidth > viewportWidthPx) {
                max(0f, viewportWidthPx) / 2f
            } else {
                0f
            }
            val totalWidth = measuredContentWidth + focusRunwayWidth
            var position = if (isRtl) totalWidth else 0f
            val geometries = words.mapIndexed { index, word ->
                val laneLeft = if (isRtl) {
                    position -= word.laneWidthPx
                    position
                } else {
                    position
                }
                val laneRight = laneLeft + word.laneWidthPx
                val glyphWidth = word.glyphWidthPx.coerceAtMost(word.laneWidthPx)
                val glyphLeft = laneLeft + (word.laneWidthPx - glyphWidth) / 2f
                val halfHitExtension = addedGapPx / 2f
                val hitLeft = laneLeft - if (index == 0) 0f else halfHitExtension
                val hitRight = laneRight + if (index == words.lastIndex) 0f else halfHitExtension
                val geometry = SentenceStripWordGeometry(
                    id = word.id,
                    start = word.start,
                    endExclusive = word.endExclusive,
                    laneBounds = StripRect(laneLeft, 0f, laneRight, word.baselinePx * 2f),
                    glyphBounds = StripRect(glyphLeft, 0f, glyphLeft + glyphWidth, word.baselinePx * 2f),
                    hitBounds = StripRect(hitLeft, 0f, hitRight, word.baselinePx * 2f),
                    baselinePx = word.baselinePx,
                    cursorAnchorsPx = word.cursorAnchorsPx,
                )
                if (isRtl) {
                    if (index < gapWidthsPx.size) position -= gapWidthsPx[index] + addedGapPx
                } else if (index < gapWidthsPx.size) {
                    position += word.laneWidthPx + gapWidthsPx[index] + addedGapPx
                }
                geometry
            }
            return SentenceStripGeometry(
                words = geometries,
                viewportWidthPx = viewportWidthPx,
                contentWidthPx = totalWidth,
                addedGapPx = addedGapPx,
                focusRunwayWidthPx = focusRunwayWidth,
                isRtl = isRtl,
            )
        }
    }
}
