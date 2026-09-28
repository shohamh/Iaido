package com.iaido.app

import android.graphics.Point
import android.graphics.Rect
import android.util.Xml
import androidx.test.uiautomator.UiDevice
import android.os.SystemClock
import android.util.Log
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.language.Language
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets

data class KeyboardWindow(
    val rootBounds: Rect,
    val surfaceBounds: Rect,
    val language: Language,
    val keyBounds: Map<String, Rect>,
) {
    fun keyCenter(key: String): Point = keyBounds[key]
        ?.let(::centerOf)
        ?: error("No marked key '$key'; available keys=${keyBounds.keys}")

    fun pathThroughVisibleKeys(word: String, startMs: Long = 0L, stepMs: Long = 10L): GesturePath {
        require(word.isNotEmpty()) { "Swipe word cannot be empty" }
        require(word.all(Char::isLetter)) { "Swipe word must contain letters: '$word'" }
        require(stepMs > 0L) { "stepMs must be positive" }
        return GesturePath(word.mapIndexed { index, letter ->
            val center = keyCenter(letter.toString().lowercase())
            GesturePoint(
                x = (center.x - surfaceBounds.left).toFloat(),
                y = (center.y - surfaceBounds.top).toFloat(),
                timestampMs = startMs + index * stepMs,
            )
        })
    }
}

object KeyboardWindowLocator {
    const val ROOT_DESCRIPTION = "Iaido keyboard root"
    const val SURFACE_DESCRIPTION = "Iaido swipe surface"
    const val LANGUAGE_DESCRIPTION_PREFIX = "Iaido language "
    const val KEY_DESCRIPTION_PREFIX = "Iaido key "

    // The current bottom row is globe, comma, space, period, enter. Settings is
    // opened from the Settings activity now, so it is no longer a required IME
    // key and older emulator tests must not block on it.
    private val requiredKeys = listOf("globe", "space", "backspace", "enter")
    private val boundsPattern = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    fun locate(
        device: UiDevice,
        timeoutMs: Long = 5_000L,
        expectedLanguage: Language? = null,
    ): KeyboardWindow {
        val startedAtMs = SystemClock.elapsedRealtime()
        val deadline = startedAtMs + timeoutMs
        var latestHierarchy = ""
        var locatedWindow: KeyboardWindow? = null
        var lastInvalidSnapshot: IllegalArgumentException? = null
        do {
            latestHierarchy = dumpHierarchy(device)
            val window = parseKeyboardWindowHierarchySnapshot(latestHierarchy, expectedLanguage)
            if (window != null) {
                try {
                    validateSnapshot(window, Rect(0, 0, device.displayWidth, device.displayHeight))
                    locatedWindow = window
                    break
                } catch (failure: IllegalArgumentException) {
                    // IME window bounds can move between hierarchy snapshots while the keyboard
                    // is being shown or resized. Retry a self-inconsistent snapshot instead of
                    // accepting it or failing before the existing locate timeout expires.
                    lastInvalidSnapshot = failure
                }
            }
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        val result = locatedWindow ?: throw IllegalStateException(
            lastInvalidSnapshot?.let { failure ->
                "Keyboard geometry did not settle within ${timeoutMs}ms: ${failure.message}; " +
                    "latest hierarchy=$latestHierarchy"
            } ?: missingMarkerMessage("complete keyboard hierarchy snapshot", latestHierarchy),
            lastInvalidSnapshot,
        )
        Log.i(
            "E2E-PERF",
            "phase=keyboard_locator durationMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                "language=${result.language.name}",
        )
        return result
    }

    internal fun validateSnapshot(window: KeyboardWindow, displayBounds: Rect) {
        validateBounds("root", window.rootBounds, displayBounds)
        validateBounds("surface", window.surfaceBounds, window.rootBounds)
        window.keyBounds.forEach { (key, bounds) ->
            validateBounds("key '$key'", bounds, window.surfaceBounds)
        }
    }

    private fun validateBounds(name: String, bounds: Rect, container: Rect) {
        require(!bounds.isEmpty) { "Marked $name has empty bounds: $bounds" }
        require(container.contains(bounds)) {
            "Marked $name bounds $bounds are outside container $container"
        }
    }

    private fun dumpHierarchy(device: UiDevice): String = ByteArrayOutputStream().let { output ->
        device.dumpWindowHierarchy(output)
        output.toString(StandardCharsets.UTF_8.name())
    }

    private fun parseBounds(value: String?): Rect? {
        val match = value?.let(boundsPattern::matchEntire) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
        )
    }

    /**
     * Compose occasionally reports a keyboard key's cached semantics bounds one row above its
     * rendered label while the IME window is being resized. Use the label's live bounds to
     * correct that key's vertical center; icon-only keys keep their own bounds.
     */
    private fun alignKeyWithLabel(keyBounds: Rect, labelBounds: Rect?): Rect {
        if (labelBounds == null || labelBounds.centerY() in keyBounds.top until keyBounds.bottom) {
            return keyBounds
        }
        val top = labelBounds.centerY() - keyBounds.height() / 2
        return Rect(keyBounds.left, top, keyBounds.right, top + keyBounds.height())
    }

    /**
     * The parent surface can carry the same stale vertical offset as its first key row. Its
     * height and horizontal bounds remain current, so anchor its bottom to the rendered bottom
     * row before validating the snapshot.
     */
    private fun alignSurfaceWithKeys(surfaceBounds: Rect, keyBounds: Map<String, Rect>): Rect {
        val renderedBottom = keyBounds.values.maxOfOrNull(Rect::bottom) ?: return surfaceBounds
        val bottom = maxOf(surfaceBounds.bottom, renderedBottom)
        if (bottom == surfaceBounds.bottom) return surfaceBounds
        return Rect(
            surfaceBounds.left,
            bottom - surfaceBounds.height(),
            surfaceBounds.right,
            bottom,
        )
    }

    internal fun parseKeyboardWindowHierarchySnapshot(
        hierarchy: String,
        expectedLanguage: Language? = null,
    ): KeyboardWindow? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(hierarchy))

        var rootDepth = -1
        var rootBounds: Rect? = null
        var surfaceBounds: Rect? = null
        var language: Language? = null
        val keyBounds = mutableMapOf<String, Rect>()
        var activeKey: String? = null
        var activeKeyDepth = -1
        var activeKeyBounds: Rect? = null
        var activeKeyLabelBounds: Rect? = null

        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    if (parser.name != "node") continue
                    val description = parser.getAttributeValue(null, "content-desc").orEmpty()
                    if (rootDepth < 0 && description == ROOT_DESCRIPTION) {
                        rootDepth = parser.depth
                        rootBounds = parseBounds(parser.getAttributeValue(null, "bounds"))
                        surfaceBounds = null
                        language = null
                        keyBounds.clear()
                    } else if (rootDepth >= 0 && parser.depth > rootDepth) {
                        when {
                            description == SURFACE_DESCRIPTION -> {
                                surfaceBounds = parseBounds(parser.getAttributeValue(null, "bounds"))
                            }
                            description.startsWith(LANGUAGE_DESCRIPTION_PREFIX) -> {
                                language = description.removePrefix(LANGUAGE_DESCRIPTION_PREFIX)
                                    .let { name -> runCatching { Language.valueOf(name) }.getOrNull() }
                            }
                            description.startsWith(KEY_DESCRIPTION_PREFIX) && activeKey == null -> {
                                activeKey = description.removePrefix(KEY_DESCRIPTION_PREFIX)
                                activeKeyDepth = parser.depth
                                activeKeyBounds = parseBounds(parser.getAttributeValue(null, "bounds"))
                            }
                            activeKey != null && parser.depth > activeKeyDepth &&
                                activeKeyLabelBounds == null &&
                                parser.getAttributeValue(null, "class") == "android.widget.TextView" -> {
                                activeKeyLabelBounds = parseBounds(parser.getAttributeValue(null, "bounds"))
                            }
                        }
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name == "node" && activeKey != null && parser.depth == activeKeyDepth) {
                        activeKeyBounds?.let { bounds ->
                            keyBounds[activeKey!!] = alignKeyWithLabel(bounds, activeKeyLabelBounds)
                        }
                        activeKey = null
                        activeKeyDepth = -1
                        activeKeyBounds = null
                        activeKeyLabelBounds = null
                    }
                    if (parser.name == "node" && rootDepth == parser.depth) {
                        val resolvedLanguage = expectedLanguage ?: language
                        if (rootBounds != null && surfaceBounds != null && resolvedLanguage != null &&
                            requiredKeys.all(keyBounds::containsKey)
                        ) {
                            return KeyboardWindow(
                                rootBounds,
                                alignSurfaceWithKeys(surfaceBounds, keyBounds),
                                resolvedLanguage,
                                keyBounds.toMap(),
                            )
                        }
                        rootDepth = -1
                        rootBounds = null
                        surfaceBounds = null
                        language = null
                        keyBounds.clear()
                    }
                }
            }
        }
        return null
    }
}

internal fun centerOf(bounds: Rect): Point = Point(
    (bounds.left + bounds.right) / 2,
    (bounds.top + bounds.bottom) / 2,
)

internal fun missingMarkerMessage(name: String, hierarchy: String): String =
    "Missing $name in UiAutomator hierarchy:\n$hierarchy"

internal fun navigationSafeBottom(displayBounds: Rect, navigationInsetPx: Int): Int =
    (displayBounds.bottom - navigationInsetPx.coerceAtLeast(0)).coerceAtLeast(displayBounds.top)
