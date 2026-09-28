package com.iaido.app

import android.app.Instrumentation
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.text.TextPaint
import android.view.MotionEvent
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * Pointer-level driver for the sentence-first strip's stable accessibility contract.
 * The semantic descriptions are intentionally independent of Compose implementation details.
 */
internal class SentenceStripImeDriver(
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
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

    fun alternative(
        index: Int,
        side: Side,
        expectedText: String? = null,
        timeoutMs: Long = 2_000L,
    ): SentenceAlternativeNode {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var candidates = emptyList<SentenceAlternativeNode>()
        do {
            candidates = alternativesForWord(index)
            candidates.firstOrNull { candidate ->
                candidate.contentDescription.contains("side=${side.label}") &&
                    (expectedText == null || candidate.contentDescription
                        .substringAfter(" text=")
                        .equals(expectedText, ignoreCase = true))
            }?.let { return it }
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(
            "Missing ${side.label} alternative${expectedText?.let { " '$it'" }.orEmpty()} " +
                "for sentence word $index; found ${candidates.map { it.contentDescription }}",
        )
    }

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

    fun captureScreenshot(name: String) = ArtifactWriter.captureScreenshot(name, device)

    fun screenshotShowsJoinOutlineAcross(sourceBounds: Rect): Boolean {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return false
        return try {
            val viewport = viewportBounds()
            val density = instrumentationDensity()
            val xTolerance = (density * 6f).toInt().coerceAtLeast(4)
            val minimumRun = (density * 24f).toInt().coerceAtLeast(48)
            val searchTop = (minOf(viewport.top, sourceBounds.top) - density.toInt() * 8).coerceAtLeast(0)
            val searchBottom = (maxOf(viewport.bottom, sourceBounds.bottom) + density.toInt() * 8)
                .coerceAtMost(bitmap.height)

            data class VerticalEdge(val x: Int, val top: Int, val bottom: Int, val run: Int)

            fun edgeNear(targetX: Int): VerticalEdge? {
                val firstX = (targetX - xTolerance).coerceAtLeast(0)
                val lastX = (targetX + xTolerance).coerceAtMost(bitmap.width - 1)
                val edges = (firstX..lastX).mapNotNull { x ->
                    var bestRun = 0
                    var bestTop = 0
                    var currentRun = 0
                    var currentTop = 0
                    for (y in searchTop until searchBottom) {
                        if (isJoinAccent(bitmap.getPixel(x, y))) {
                            if (currentRun == 0) currentTop = y
                            currentRun += 1
                            if (currentRun > bestRun) {
                                bestRun = currentRun
                                bestTop = currentTop
                            }
                        } else {
                            currentRun = 0
                        }
                    }
                    if (bestRun >= minimumRun) VerticalEdge(x, bestTop, bestTop + bestRun, bestRun) else null
                }
                val sourceCenterX = (sourceBounds.left + sourceBounds.right) / 2
                return if (targetX < sourceCenterX) {
                    edges.minByOrNull(VerticalEdge::x)
                } else {
                    edges.maxByOrNull(VerticalEdge::x)
                }
            }

            val left = edgeNear(sourceBounds.left) ?: return false
            val right = edgeNear(sourceBounds.right - 1) ?: return false
            val sourceCenterY = (sourceBounds.top + sourceBounds.bottom) / 2
            left.x <= sourceBounds.left + 1 && right.x + 1 >= sourceBounds.right - 1 &&
                left.top <= sourceCenterY && left.bottom > sourceCenterY &&
                right.top <= sourceCenterY && right.bottom > sourceCenterY
        } finally {
            bitmap.recycle()
        }
    }

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
        var stableBounds: Rect? = null
        do {
            lastBounds = renderedWordBounds()[index]
            viewport = viewportBounds()
            if (lastBounds != null && viewport.contains(lastBounds)) {
                if (stableBounds == lastBounds) return Rect(lastBounds)
                stableBounds = Rect(lastBounds)
            } else {
                stableBounds = null
            }
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(
            "Sentence word $index did not settle fully inside viewport: " +
                "word=$lastBounds viewport=$viewport layout=${layoutDescription()}",
        )
    }

    fun visibleAlternativeWordIndices(): List<Int> {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return emptyList()
        return try {
            val viewport = viewportBounds()
            renderedWordBounds().keys.filter { index ->
                alternativesForWord(index).any { alternative ->
                    val bounds = Rect(alternative.visibleBounds)
                    if (!bounds.intersect(viewport) || !bounds.intersect(0, 0, bitmap.width, bitmap.height)) {
                        false
                    } else {
                        screenshotContainsAlternativeInk(bitmap, bounds)
                    }
                }
            }.sorted()
        } finally {
            bitmap.recycle()
        }
    }

    fun waitForVisibleAlternativeWordIndices(
        minimumCount: Int,
        timeoutMs: Long = 2_000L,
    ): List<Int> {
        require(minimumCount > 0)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var visible = emptyList<Int>()
        do {
            visible = visibleAlternativeWordIndices()
            if (visible.size >= minimumCount) return visible
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return visible
    }

    private fun screenshotContainsAlternativeInk(bitmap: android.graphics.Bitmap, bounds: Rect): Boolean {
        if (bounds.width() < 5 || bounds.height() < 5) return false
        val sampleX = bounds.left + 2
        val sampleY = bounds.top + 2
        val background = bitmap.getPixel(sampleX, sampleY)
        var contrastingPixels = 0
        for (y in (bounds.top + 2) until (bounds.bottom - 2)) {
            for (x in (bounds.left + 2) until (bounds.right - 2)) {
                val pixel = bitmap.getPixel(x, y)
                val difference = kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(background)) +
                    kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(background)) +
                    kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(background))
                if (difference >= 48 && ++contrastingPixels >= 12) return true
            }
        }
        return false
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

    fun waitForEditorTextChange(
        previous: String,
        timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS,
    ): String = editor.waitForTextChange(previous, timeoutMs)

    fun tapWord(index: Int) {
        val bounds = waitForWordWithinViewport(index)
        tapCenter(bounds)
        device.waitForIdle()
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        var lastSnapshot: ImeEditorSnapshot? = null
        var lastCursor: Int? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            lastSnapshot = editorSnapshot()
            lastCursor = cursorOffset()
            if (lastSnapshot.selection.first == lastSnapshot.selection.last &&
                lastCursor == lastSnapshot.selection.last
            ) return
            SystemClock.sleep(50L)
        }
        error(
            "Word tap did not settle the host and strip caret together: " +
                "word=$index bounds=$bounds editor=$lastSnapshot stripCursor=$lastCursor",
        )
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

    fun swipeAlternative(
        index: Int,
        side: Side,
        expectedAlternativeText: String? = null,
        whilePreviewing: () -> Unit,
    ) {
        val start = word(index).visibleBounds
        val target = alternative(index, side, expectedAlternativeText).visibleBounds
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

    fun waitForNodeOrNull(prefix: String, timeoutMs: Long = 1_000L): UiObject2? =
        device.wait(Until.findObject(By.descStartsWith(prefix)), timeoutMs)

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

    private fun isJoinAccent(pixel: Int): Boolean =
        android.graphics.Color.alpha(pixel) >= 220 &&
            kotlin.math.abs(android.graphics.Color.red(pixel) - 139) <= 32 &&
            kotlin.math.abs(android.graphics.Color.green(pixel) - 203) <= 32 &&
            kotlin.math.abs(android.graphics.Color.blue(pixel) - 208) <= 32

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
