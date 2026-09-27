package com.iaido.app

import android.app.Instrumentation
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.text.TextPaint
import android.view.MotionEvent
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * Pointer-level driver for the sentence-first strip's stable accessibility contract.
 * The semantic descriptions are intentionally independent of Compose implementation details.
 */
internal class SentenceStripImeDriver(
    instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
) {
    private val device = UiDevice.getInstance(instrumentation)
    private val pointer = PointerInjector(instrumentation.uiAutomation)
    private val editor = ImeEditorDriver(device, pointer, instrumentation.targetContext.packageName)

    fun strip(): UiObject2 = requiredNode("Iaido sentence strip")

    fun word(index: Int): SentenceWordNode {
        val directNode = device.findObject(By.descStartsWith("Iaido sentence word index=$index "))
            ?: device.findObject(By.descStartsWith("Iaido sentence glyph word index=$index "))
        val description = debugWordCandidateDescription(index)
            ?: directNode?.contentDescription?.toString()
            ?: error("Missing measured sentence word $index")
        val bounds = renderedWordBounds()[index] ?: directNode?.visibleBounds
            ?: error("Missing rendered bounds for sentence word $index")
        return SentenceWordNode(description, bounds)
    }

    fun alternative(index: Int, side: Side): SentenceAlternativeNode = alternativesForWord(index)
        .firstOrNull { it.contentDescription.contains("side=${side.label}") }
        ?: error("Missing ${side.label} alternative for sentence word $index")

    fun cursorOffset(): Int = requiredNode("Iaido sentence cursor offset=")
        .contentDescription
        .orEmpty()
        .substringAfter("offset=")
        .toInt()

    fun previewTextOrNull(): String? = device.findObject(By.descStartsWith("Iaido sentence strip"))
        ?.contentDescription
        ?.toString()
        ?.substringAfter(" preview text=", "")
        ?.takeIf(String::isNotBlank)

    fun joinPreviewOrNull(): UiObject2? = device.findObject(By.descStartsWith("Iaido join preview text="))

    fun deletionPreviewOrNull(): UiObject2? = device.findObject(By.descStartsWith("Iaido deletion preview "))

    fun renderedWordBounds(): Map<Int, Rect> {
        val description = layoutDescription() ?: return emptyMap()
        return Regex("(\\d+):(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)")
            .findAll(description)
            .mapNotNull { match ->
                val (index, left, top, right, bottom) = match.groupValues.drop(1).map(String::toInt)
                if (right <= left || bottom <= top) null else index to Rect(left, top, right, bottom)
            }
            .toMap()
    }

    fun viewportBounds(): Rect {
        val description = layoutDescription() ?: error("Missing sentence strip layout diagnostics")
        val match = Regex(" viewport=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)").find(description)
            ?: error("Missing sentence strip viewport bounds: $description")
        val values = match.groupValues.drop(1).map(String::toInt)
        return Rect(values[0], values[1], values[2], values[3])
    }

    fun waitForWordWithinViewport(
        index: Int,
        timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS,
    ): Rect {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastBounds: Rect? = null
        var viewport = Rect()
        do {
            lastBounds = renderedWordBounds()[index]
            viewport = viewportBounds()
            if (lastBounds != null && viewport.contains(lastBounds)) return lastBounds
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Sentence word $index did not settle fully inside viewport: word=$lastBounds viewport=$viewport")
    }

    fun visibleAlternativeWordIndices(): List<Int> {
        val viewport = viewportBounds()
        return renderedWordBounds().filterValues { bounds ->
            val visible = Rect(bounds)
            visible.intersect(viewport)
        }.keys.filter { index -> alternativesForWord(index).isNotEmpty() }.sorted()
    }

    fun renderedDeletionBoundsOrNull(): RenderedDeletionBounds? {
        val description = layoutDescription() ?: return null
        val match = Regex(
            " preview=(\\d+),(\\d+),(\\d+),(\\d+):(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)",
        ).find(description) ?: return null
        val values = match.groupValues.drop(1).map(String::toInt)
        val bounds = Rect(values[4], values[5], values[6], values[7])
        if (bounds.isEmpty) return null
        return RenderedDeletionBounds(
            startWord = values[0],
            endWord = values[1],
            sourceStart = values[2],
            sourceEndExclusive = values[3],
            visibleBounds = bounds,
        )
    }

    private fun layoutDescription(): String? = runCatching {
        device.findObject(By.descStartsWith("Iaido sentence strip"))
            ?.contentDescription
            ?.toString()
            ?.substringAfter("layout bounds=", "")
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun debugWordCandidateDescription(index: Int): String? {
        val layout = layoutDescription() ?: return null
        val match = Regex(
            "(?:^|;)$index:-?\\d+,-?\\d+,-?\\d+,-?\\d+:above=([^;]*):below=(.*?)(?=;\\d+:| viewport=| preview=|$)",
        ).find(layout) ?: return null
        return "Iaido sentence word index=$index above=${match.groupValues[1]} " +
            "below=${match.groupValues[2]}"
    }

    fun waitForRenderedDeletionBounds(
        timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS,
    ): RenderedDeletionBounds {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            renderedDeletionBoundsOrNull()?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        val visibleSentenceNodes = runCatching {
            device.findObjects(By.descStartsWith("Iaido sentence"))
                .mapNotNull { node -> runCatching { node.contentDescription?.toString() }.getOrNull() }
        }.getOrDefault(emptyList())
        error("Deletion outline geometry did not settle; visible sentence semantics=$visibleSentenceNodes")
    }

    fun waitForRenderedWordBounds(
        minimumCount: Int,
        timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS,
    ): Map<Int, Rect> {
        require(minimumCount > 0)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastBounds = emptyMap<Int, Rect>()
        do {
            lastBounds = renderedWordBounds()
            if (lastBounds.size >= minimumCount) return lastBounds
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        val visibleSentenceNodes = device.findObjects(By.descStartsWith("Iaido sentence"))
            .mapNotNull { node -> runCatching { node.contentDescription?.toString() }.getOrNull() }
        error(
            "Expected at least $minimumCount measured sentence words, found ${lastBounds.keys}; " +
                "visible sentence semantics=$visibleSentenceNodes",
        )
    }

    fun alternativesForWord(index: Int): List<SentenceAlternativeNode> {
        val directNodes = runCatching {
            device.findObjects(By.descStartsWith("Iaido sentence alternative word=$index "))
                .mapNotNull { node ->
                    runCatching {
                        SentenceAlternativeNode(
                            node.contentDescription?.toString().orEmpty(),
                            node.visibleBounds,
                        )
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())
        if (directNodes.isNotEmpty()) {
            return directNodes
        }

        // The IME's horizontal scroll container merges lane descendants in the
        // platform accessibility tree. Candidate names are on the merged lane;
        // reconstruct their hit rows from that lane's measured bounds.
        val lane = word(index)
        val description = lane.contentDescription
        val above = description.substringAfter(" above=", "").substringBefore(" below=")
        val below = description.substringAfter(" below=", "")
        val density = instrumentationDensity()
        val rowHeight = (20f * density).toInt().coerceAtLeast(1)
        val bounds = lane.visibleBounds
        val aboveTop = bounds.top
        val belowTop = bounds.bottom - rowHeight
        return buildList {
            if (above.isNotBlank()) {
                add(
                    SentenceAlternativeNode(
                        "Iaido sentence alternative word=$index side=above text=$above",
                        Rect(bounds.left, aboveTop, bounds.right, aboveTop + rowHeight),
                    ),
                )
            }
            if (below.isNotBlank()) {
                add(
                    SentenceAlternativeNode(
                        "Iaido sentence alternative word=$index side=below text=$below",
                        Rect(bounds.left, belowTop, bounds.right, belowTop + rowHeight),
                    ),
                )
            }
        }
    }

    fun sideForAlternative(index: Int, text: String): Side? = alternativesForWord(index)
        .firstOrNull { node ->
            node.contentDescription.orEmpty().substringAfter("text=").equals(text, ignoreCase = true)
        }
        ?.contentDescription
        ?.let { description ->
            if (description.contains("side=above")) Side.ABOVE else Side.BELOW
        }

    fun visibleWordIndices(): List<Int> = runCatching {
        device.findObjects(By.descStartsWith("Iaido sentence word index="))
            .mapNotNull { node ->
                runCatching {
                    Regex("index=(\\d+)").find(node.contentDescription.orEmpty())
                        ?.groupValues?.get(1)?.toIntOrNull()
                }.getOrNull()
            }
    }.getOrDefault(emptyList()).ifEmpty { renderedWordBounds().keys.sorted() }

    fun undoEnabled(): Boolean = requiredNode("Iaido undo").isEnabled

    fun redoEnabled(): Boolean = requiredNode("Iaido redo").isEnabled

    fun editorSnapshot(): ImeEditorSnapshot = editor.snapshot()

    fun tapWord(index: Int) {
        tapCenter(word(index).visibleBounds)
        device.waitForIdle()
        SystemClock.sleep(120L)
    }

    fun tapCharacter(index: Int, renderedText: String, characterIndex: Int) {
        require(characterIndex in renderedText.indices)
        val bounds = word(index).visibleBounds
        val paint = TextPaint().apply {
            textSize = 18f * instrumentationDensity()
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        val prefixWidth = paint.measureText(renderedText.substring(0, characterIndex))
        val characterWidth = paint.measureText(renderedText.substring(characterIndex, characterIndex + 1))
        val textWidth = paint.measureText(renderedText).coerceAtMost(bounds.width().toFloat())
        val left = (bounds.left + (bounds.width() - textWidth) / 2f)
        pointer.injectTap(left + prefixWidth + characterWidth * 0.15f, bounds.exactCenterY())
    }

    fun tapGap(firstWordIndex: Int, secondWordIndex: Int) {
        val first = word(firstWordIndex).visibleBounds
        val second = word(secondWordIndex).visibleBounds
        val x = if (first.right <= second.left) {
            (first.right + second.left) / 2f
        } else {
            (second.right + first.left) / 2f
        }
        pointer.injectTap(x, first.exactCenterY())
    }

    fun swipeAlternative(index: Int, side: Side, whilePreviewing: () -> Unit) {
        val start = word(index).visibleBounds
        val target = alternative(index, side).visibleBounds
        dragAndObserve(
            PointF(start.exactCenterX(), start.exactCenterY()),
            PointF(target.exactCenterX(), target.exactCenterY()),
            whilePreviewing,
        )
    }

    fun swipeAlternativeBackToOrigin(
        index: Int,
        side: Side,
        whileOnAlternative: () -> Unit,
        whileBackAtOrigin: () -> Unit,
    ) {
        val start = word(index).visibleBounds.let { PointF(it.exactCenterX(), it.exactCenterY()) }
        val target = alternative(index, side).visibleBounds.let { PointF(it.exactCenterX(), it.exactCenterY()) }
        var reachedAlternative = false
        var returnedToOrigin = false
        pointer.injectScreenSwipe(
            points = listOf(start, target, target, start, start),
            stepMs = 64L,
            onEvent = { event ->
                if (event.action != MotionEvent.ACTION_MOVE) return@injectScreenSwipe
                val position = event.pointers.single()
                fun isNear(point: PointF) =
                    kotlin.math.abs(position.x - point.x) <= 4f &&
                        kotlin.math.abs(position.y - point.y) <= 4f

                if (!reachedAlternative && isNear(target)) {
                    reachedAlternative = true
                    SystemClock.sleep(160L)
                    whileOnAlternative()
                } else if (reachedAlternative && !returnedToOrigin && isNear(start)) {
                    returnedToOrigin = true
                    SystemClock.sleep(160L)
                    whileBackAtOrigin()
                }
            },
        )
        check(reachedAlternative) { "The injected path did not reach the alternative lane" }
        check(returnedToOrigin) { "The injected path did not return to the source word" }
        device.waitForIdle()
    }

    fun swipeAlternativeThenCancel(index: Int, side: Side, whilePreviewing: () -> Unit) {
        val start = word(index).visibleBounds
        val target = alternative(index, side).visibleBounds
        dragPathAndObserve(
            listOf(
                PointF(start.exactCenterX(), start.exactCenterY()),
                PointF(target.exactCenterX(), target.exactCenterY()),
                PointF(target.exactCenterX(), target.exactCenterY()),
            ),
            PointF(target.exactCenterX(), target.exactCenterY()),
            whilePreviewing,
            cancel = true,
        )
    }

    fun dragAcrossWords(
        startIndex: Int,
        endIndex: Int,
        side: Side = Side.ABOVE,
        whilePreviewing: () -> Unit,
    ) {
        val bounds = renderedWordBounds()
        val start = bounds[startIndex] ?: word(startIndex).visibleBounds
        val target = bounds[endIndex] ?: word(endIndex).visibleBounds
        dragAcrossBounds(start, target, side, whilePreviewing)
    }

    fun dragAcrossRenderedWords(
        startIndex: Int,
        endIndex: Int,
        wordBounds: Map<Int, Rect>,
        side: Side = Side.ABOVE,
        whilePreviewing: () -> Unit,
    ) {
        val start = wordBounds[startIndex]
            ?: error("Measured sentence lane $startIndex was not exposed")
        val target = wordBounds[endIndex]
            ?: error("Measured sentence lane $endIndex was not exposed")
        dragAcrossBounds(start, target, side, whilePreviewing)
    }

    private fun dragAcrossBounds(
        start: Rect,
        target: Rect,
        side: Side,
        whilePreviewing: () -> Unit,
    ) {
        val sign = if (side == Side.ABOVE) -1f else 1f
        val lifted = PointF(start.exactCenterX(), start.exactCenterY() + sign * 18f * instrumentationDensity())
        dragPathAndObserve(
            listOf(
                PointF(start.exactCenterX(), start.exactCenterY()),
                lifted,
                PointF(target.exactCenterX(), lifted.y),
            ),
            PointF(target.exactCenterX(), lifted.y),
            whilePreviewing,
        )
    }

    fun swipeApproximateHebrewDeletion(whilePreviewing: () -> Unit) {
        val bounds = strip().visibleBounds
        val y = bounds.top + bounds.height() * 0.55f
        val start = PointF(bounds.right - 300f, y)
        val lifted = PointF(start.x, y - 24f * instrumentationDensity())
        val target = PointF(bounds.right - 150f, lifted.y)
        dragPathAndObserve(
            listOf(start, lifted, target),
            target,
            whilePreviewing,
        )
    }

    fun horizontalStripSwipe(direction: Direction) {
        val bounds = strip().visibleBounds
        val centerY = bounds.exactCenterY()
        val inset = (bounds.width() * 0.12f).coerceAtLeast(24f)
        val left = PointF(bounds.left + inset, centerY)
        val right = PointF(bounds.right - inset, centerY)
        val points = if (direction == Direction.LEFT) listOf(right, left) else listOf(left, right)
        pointer.injectScreenSwipe(points, stepMs = 48L)
    }

    fun dragToEdgeZone(direction: Direction, from: Rect, whilePreviewing: () -> Unit) {
        val edge = edgeZonePoint(direction, from.exactCenterY())
        dragAndObserve(
            PointF(from.exactCenterX(), from.exactCenterY()),
            edge,
            whilePreviewing,
            holdBeforeMoveMs = 480L,
        )
    }

    fun holdWordAndDragToEdge(index: Int, direction: Direction, whilePreviewing: () -> Unit) {
        val start = word(index).visibleBounds
        val startPoint = PointF(start.exactCenterX(), start.exactCenterY())
        val endPoint = edgeZonePoint(direction, start.exactCenterY())
        dragPathAndObserve(
            listOf(startPoint, startPoint, endPoint, endPoint),
            endPoint,
            whilePreviewing,
            holdBeforeMoveMs = 480L,
            holdAfterMoveMs = 600L,
        )
    }

    fun holdStripAndDragToPhysicalEdge(direction: Direction, whilePreviewing: () -> Unit) {
        val bounds = strip().visibleBounds
        val start = PointF(bounds.exactCenterX(), bounds.exactCenterY())
        val edgeX = if (direction == Direction.RIGHT) bounds.right - 8f else bounds.left + 8f
        val edge = PointF(edgeX, bounds.exactCenterY())
        dragPathAndObserve(
            listOf(start, start, edge, edge),
            edge,
            whilePreviewing,
            holdBeforeMoveMs = 480L,
            holdAfterMoveMs = 600L,
        )
    }

    fun tapUndo() = tapCenter(requiredNode("Iaido undo").visibleBounds)

    fun tapRedo() = tapCenter(requiredNode("Iaido redo").visibleBounds)

    fun holdUndo(whilePreviewing: () -> Unit) = holdAndObserve(
        requiredNode("Iaido undo").visibleBounds,
        whilePreviewing,
    )

    fun holdRedo(whilePreviewing: () -> Unit) = holdAndObserve(
        requiredNode("Iaido redo").visibleBounds,
        whilePreviewing,
    )

    fun nodeOrNull(prefix: String): UiObject2? = device.findObject(By.descStartsWith(prefix))

    fun requireNode(prefix: String): UiObject2 = requiredNode(prefix)

    private fun dragAndObserve(
        from: PointF,
        to: PointF,
        whilePreviewing: () -> Unit,
        holdBeforeMoveMs: Long = 0L,
        holdAfterMoveMs: Long = 120L,
        cancel: Boolean = false,
    ) = dragPathAndObserve(
        listOf(from, to, to),
        to,
        whilePreviewing,
        holdBeforeMoveMs,
        holdAfterMoveMs,
        cancel,
    )

    private fun dragPathAndObserve(
        points: List<PointF>,
        observeAt: PointF,
        whilePreviewing: () -> Unit,
        holdBeforeMoveMs: Long = 0L,
        holdAfterMoveMs: Long = 120L,
        cancel: Boolean = false,
    ) {
        var observed = false
        pointer.injectScreenSwipe(
            points = points,
            stepMs = 64L,
            holdBeforeMoveMs = holdBeforeMoveMs,
            cancel = cancel,
            onEvent = { event ->
                if (!observed && event.action == MotionEvent.ACTION_MOVE) {
                    val last = event.pointers.last()
                    if (kotlin.math.abs(last.x - observeAt.x) <= 2f &&
                        kotlin.math.abs(last.y - observeAt.y) <= 2f
                    ) {
                        observed = true
                        SystemClock.sleep(holdAfterMoveMs)
                        whilePreviewing()
                    }
                }
            },
        )
        check(observed) { "Pointer path never reached its held preview point $observeAt" }
    }

    private fun holdAndObserve(bounds: Rect, whilePreviewing: () -> Unit) {
        val center = PointF(bounds.exactCenterX(), bounds.exactCenterY())
        var observed = false
        pointer.injectScreenSwipe(
            points = listOf(center, center),
            stepMs = 48L,
            holdBeforeMoveMs = 480L,
            onEvent = { event ->
                if (!observed && event.action == MotionEvent.ACTION_MOVE) {
                    observed = true
                    whilePreviewing()
                }
            },
        )
        check(observed) { "Long-press path did not expose its preview while held" }
    }

    private fun tapCenter(bounds: Rect) {
        pointer.injectTap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun edgeZonePoint(direction: Direction, y: Float): PointF {
        val viewport = strip().visibleBounds
        val inset = (36f * instrumentationDensity()).toInt()
        val x = when (direction) {
            Direction.LEFT -> viewport.left + inset
            Direction.RIGHT -> viewport.right - inset
        }
        return PointF(x.toFloat(), y)
    }

    private fun requiredNode(prefix: String): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            device.findObject(By.descStartsWith(prefix))?.let { return it }
            SystemClock.sleep(50L)
        }
        val visible = device.findObjects(By.descStartsWith("Iaido sentence"))
            .mapNotNull { runCatching { it.contentDescription?.toString() }.getOrNull() }
        error("Missing sentence-strip semantic '$prefix'; visible sentence semantics=$visible")
    }

    private fun instrumentationDensity(): Float =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    enum class Side(val label: String) {
        ABOVE("above"),
        BELOW("below"),
    }

    enum class Direction(val label: String) {
        LEFT("left"),
        RIGHT("right"),
    }
}

internal data class RenderedDeletionBounds(
    val startWord: Int,
    val endWord: Int,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val visibleBounds: Rect,
)

internal data class SentenceAlternativeNode(
    val contentDescription: String,
    val visibleBounds: Rect,
)

internal data class SentenceWordNode(
    val contentDescription: String,
    val visibleBounds: Rect,
)
