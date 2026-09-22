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

    fun word(index: Int): UiObject2 = requiredNode("Iaido sentence word index=$index")

    fun alternative(index: Int, side: Side): UiObject2 = requiredNode(
        "Iaido sentence alternative word=$index side=${side.label}",
    )

    fun cursorOffset(): Int = requiredNode("Iaido sentence cursor offset=")
        .contentDescription
        .orEmpty()
        .substringAfter("offset=")
        .toInt()

    fun previewTextOrNull(): String? = device.findObject(By.descStartsWith("Iaido sentence preview text="))
        ?.contentDescription
        ?.toString()
        ?.substringAfter("text=")

    fun joinPreviewOrNull(): UiObject2? = device.findObject(By.descStartsWith("Iaido join preview text="))

    fun deletionPreviewOrNull(): UiObject2? = device.findObject(By.descStartsWith("Iaido deletion preview "))

    fun alternativesForWord(index: Int): List<UiObject2> = device.findObjects(
        By.descStartsWith("Iaido sentence alternative word=$index "),
    )

    fun sideForAlternative(index: Int, text: String): Side? = alternativesForWord(index)
        .firstOrNull { node ->
            node.contentDescription.orEmpty().substringAfter("text=").equals(text, ignoreCase = true)
        }
        ?.contentDescription
        ?.let { description ->
            if (description.contains("side=above")) Side.ABOVE else Side.BELOW
        }

    fun visibleWordIndices(): List<Int> = device.findObjects(By.descStartsWith("Iaido sentence word index="))
        .mapNotNull { node ->
            Regex("index=(\\d+)").find(node.contentDescription.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        }

    fun undoEnabled(): Boolean = requiredNode("Iaido undo").isEnabled

    fun redoEnabled(): Boolean = requiredNode("Iaido redo").isEnabled

    fun editorSnapshot(): ImeEditorSnapshot = editor.snapshot()

    fun tapWord(index: Int) = tapCenter(word(index).visibleBounds)

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
        val start = word(startIndex).visibleBounds
        val target = word(endIndex).visibleBounds
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
        val edge = requiredNode("Iaido edge zone direction=${direction.label}").visibleBounds
        dragAndObserve(
            PointF(from.exactCenterX(), from.exactCenterY()),
            PointF(edge.exactCenterX(), edge.exactCenterY()),
            whilePreviewing,
            holdBeforeMoveMs = 480L,
        )
    }

    fun holdWordAndDragToEdge(index: Int, direction: Direction, whilePreviewing: () -> Unit) {
        val start = word(index).visibleBounds
        val edge = requiredNode("Iaido edge zone direction=${direction.label}").visibleBounds
        val startPoint = PointF(start.exactCenterX(), start.exactCenterY())
        val endPoint = PointF(edge.exactCenterX(), edge.exactCenterY())
        dragPathAndObserve(
            listOf(startPoint, startPoint, endPoint, endPoint),
            endPoint,
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
