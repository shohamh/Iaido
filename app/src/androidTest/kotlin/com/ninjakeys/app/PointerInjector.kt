package com.ninjakeys.app

import android.app.UiAutomation
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.ninjakeys.core.gesture.GesturePoint
import kotlin.random.Random

data class PointerSample(
    val id: Int,
    val x: Float,
    val y: Float,
)

data class InjectedPointerEvent(
    val action: Int,
    val actionIndex: Int,
    val eventTimeMs: Long,
    val pointers: List<PointerSample>,
)

class PointerInjector(private val automation: UiAutomation) {
    fun injectSwipe(
        points: List<GesturePoint>,
        surfaceBounds: Rect,
        startTimeMs: Long = SystemClock.uptimeMillis(),
        stepMs: Long = DEFAULT_STEP_MS,
        jitterSeed: Long = 0L,
        jitterPx: Float = 0f,
        cancel: Boolean = false,
    ): List<InjectedPointerEvent> {
        return buildSwipeEvents(points, surfaceBounds, startTimeMs, stepMs, jitterSeed, jitterPx, cancel)
            .also(::inject)
    }

    fun injectMultiPointer(
        paths: List<List<GesturePoint>>,
        surfaceBounds: Rect,
        startTimeMs: Long = SystemClock.uptimeMillis(),
        stepMs: Long = DEFAULT_STEP_MS,
        jitterSeed: Long = 0L,
        jitterPx: Float = 0f,
    ): List<InjectedPointerEvent> {
        return buildMultiPointerEvents(paths, surfaceBounds, startTimeMs, stepMs, jitterSeed, jitterPx)
            .also(::inject)
    }

    fun injectTap(
        centerX: Float,
        centerY: Float,
        startTimeMs: Long = SystemClock.uptimeMillis(),
    ): List<InjectedPointerEvent> {
        val sample = PointerSample(0, centerX, centerY)
        return listOf(
            InjectedPointerEvent(MotionEvent.ACTION_DOWN, 0, startTimeMs, listOf(sample)),
            InjectedPointerEvent(MotionEvent.ACTION_UP, 0, startTimeMs + DEFAULT_STEP_MS, listOf(sample)),
        ).also(::inject)
    }

    fun injectScreenSwipe(
        points: List<PointF>,
        startTimeMs: Long = SystemClock.uptimeMillis(),
        stepMs: Long = DEFAULT_STEP_MS,
        cancel: Boolean = false,
    ): List<InjectedPointerEvent> = buildScreenSwipeEvents(points, startTimeMs, stepMs, cancel).also(::inject)

    fun injectLongPress(
        centerX: Float,
        centerY: Float,
        durationMs: Long = 750L,
        startTimeMs: Long = SystemClock.uptimeMillis(),
    ): List<InjectedPointerEvent> = injectScreenSwipe(
        points = listOf(PointF(centerX, centerY), PointF(centerX, centerY)),
        startTimeMs = startTimeMs,
        stepMs = durationMs,
    )

    private fun inject(events: List<InjectedPointerEvent>) {
        require(events.isNotEmpty()) { "Cannot inject an empty pointer sequence" }
        val downTime = events.first().eventTimeMs
        events.forEach { event ->
            val coordinates = Array(event.pointers.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = event.pointers[index].x
                    y = event.pointers[index].y
                    pressure = 1f
                    size = 1f
                }
            }
            val encodedAction = event.action or
                (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            val motionEvent = MotionEvent.obtain(
                downTime,
                event.eventTimeMs,
                encodedAction,
                event.pointers.size,
                event.pointers.map { it.id }.toIntArray(),
                coordinates,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            try {
                check(automation.injectInputEvent(motionEvent, true)) {
                    "UiAutomation rejected pointer event $event"
                }
            } finally {
                motionEvent.recycle()
            }
        }
    }

    companion object {
        private const val DEFAULT_STEP_MS = 16L

        fun buildSwipeEvents(
            points: List<GesturePoint>,
            surfaceBounds: Rect,
            startTimeMs: Long,
            stepMs: Long,
            jitterSeed: Long = 0L,
            jitterPx: Float = 0f,
            cancel: Boolean = false,
        ): List<InjectedPointerEvent> {
            require(points.isNotEmpty()) { "Swipe must contain at least one point" }
            require(stepMs > 0L) { "stepMs must be positive" }
            require(jitterPx >= 0f) { "jitterPx cannot be negative" }
            val random = Random(jitterSeed)
            val screenPoints = points.map { toScreenPoint(it, surfaceBounds, random, jitterPx) }
            val events = screenPoints.mapIndexedTo(mutableListOf()) { index, point ->
                InjectedPointerEvent(
                    action = if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                    actionIndex = 0,
                    eventTimeMs = startTimeMs + index * stepMs,
                    pointers = listOf(PointerSample(0, point.first, point.second)),
                )
            }
            events += InjectedPointerEvent(
                action = if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                actionIndex = 0,
                eventTimeMs = startTimeMs + screenPoints.size * stepMs,
                pointers = listOf(PointerSample(0, screenPoints.last().first, screenPoints.last().second)),
            )
            return events
        }

        fun buildMultiPointerEvents(
            paths: List<List<GesturePoint>>,
            surfaceBounds: Rect,
            startTimeMs: Long,
            stepMs: Long,
            jitterSeed: Long = 0L,
            jitterPx: Float = 0f,
        ): List<InjectedPointerEvent> {
            require(paths.size >= 2) { "Multi-pointer gesture needs at least two paths" }
            require(paths.all(List<GesturePoint>::isNotEmpty)) { "Multi-pointer paths cannot be empty" }
            require(stepMs > 0L) { "stepMs must be positive" }
            require(jitterPx >= 0f) { "jitterPx cannot be negative" }
            val random = Random(jitterSeed)
            val screenPaths = paths.map { path ->
                path.map { toScreenPoint(it, surfaceBounds, random, jitterPx) }
            }
            val maxLength = screenPaths.maxOf { it.size }
            fun samplesAt(index: Int): List<PointerSample> = screenPaths.mapIndexed { pointerId, path ->
                val point = path[index.coerceAtMost(path.lastIndex)]
                PointerSample(pointerId, point.first, point.second)
            }

            val events = mutableListOf(
                InjectedPointerEvent(
                    MotionEvent.ACTION_DOWN,
                    actionIndex = 0,
                    eventTimeMs = startTimeMs,
                    pointers = listOf(samplesAt(0).first()),
                ),
                InjectedPointerEvent(
                    MotionEvent.ACTION_POINTER_DOWN,
                    actionIndex = 1,
                    eventTimeMs = startTimeMs + stepMs,
                    pointers = samplesAt(0),
                ),
            )
            for (index in 1 until maxLength) {
                events += InjectedPointerEvent(
                    MotionEvent.ACTION_MOVE,
                    actionIndex = 0,
                    eventTimeMs = startTimeMs + (index + 1) * stepMs,
                    pointers = samplesAt(index),
                )
            }
            val lastTime = startTimeMs + (maxLength + 1L) * stepMs
            events += InjectedPointerEvent(
                MotionEvent.ACTION_POINTER_UP,
                actionIndex = 1,
                eventTimeMs = lastTime,
                pointers = samplesAt(maxLength - 1),
            )
            events += InjectedPointerEvent(
                MotionEvent.ACTION_UP,
                actionIndex = 0,
                eventTimeMs = lastTime + stepMs,
                pointers = listOf(samplesAt(maxLength - 1).first()),
            )
            return events
        }

        private fun buildScreenSwipeEvents(
            points: List<PointF>,
            startTimeMs: Long,
            stepMs: Long,
            cancel: Boolean,
        ): List<InjectedPointerEvent> {
            require(points.isNotEmpty()) { "Screen swipe must contain at least one point" }
            require(stepMs > 0L) { "stepMs must be positive" }
            val events = points.mapIndexedTo(mutableListOf()) { index, point ->
                InjectedPointerEvent(
                    action = if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                    actionIndex = 0,
                    eventTimeMs = startTimeMs + index * stepMs,
                    pointers = listOf(PointerSample(0, point.x, point.y)),
                )
            }
            events += InjectedPointerEvent(
                action = if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                actionIndex = 0,
                eventTimeMs = startTimeMs + points.size * stepMs,
                pointers = listOf(PointerSample(0, points.last().x, points.last().y)),
            )
            return events
        }

        private fun toScreenPoint(
            point: GesturePoint,
            bounds: Rect,
            random: Random,
            jitterPx: Float,
        ): Pair<Float, Float> {
            require(!bounds.isEmpty) { "Cannot transform a point into empty bounds $bounds" }
            val jitterX = if (jitterPx == 0f) 0f else (random.nextFloat() * 2f - 1f) * jitterPx
            val jitterY = if (jitterPx == 0f) 0f else (random.nextFloat() * 2f - 1f) * jitterPx
            val x = (bounds.left + point.x + jitterX).coerceIn(bounds.left.toFloat(), (bounds.right - 1).toFloat())
            val y = (bounds.top + point.y + jitterY).coerceIn(bounds.top.toFloat(), (bounds.bottom - 1).toFloat())
            return x to y
        }
    }
}
