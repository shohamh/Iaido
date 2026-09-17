package com.iaido.app

import android.graphics.Point
import android.graphics.Rect
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.os.SystemClock
import android.util.Log
import com.iaido.core.language.Language
import java.io.ByteArrayOutputStream
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
}

object KeyboardWindowLocator {
    const val ROOT_DESCRIPTION = "Iaido keyboard root"
    const val SURFACE_DESCRIPTION = "Iaido swipe surface"
    const val LANGUAGE_DESCRIPTION_PREFIX = "Iaido language "
    const val KEY_DESCRIPTION_PREFIX = "Iaido key "

    private val requiredKeys = listOf("globe", "space", "backspace")

    fun locate(device: UiDevice, timeoutMs: Long = 5_000L): KeyboardWindow {
        val startedAtMs = SystemClock.elapsedRealtime()
        if (!device.wait(Until.hasObject(By.desc(ROOT_DESCRIPTION)), timeoutMs)) {
            throw missingMarker("root '$ROOT_DESCRIPTION'", device)
        }

        val root = device.wait(Until.findObject(By.desc(ROOT_DESCRIPTION)), timeoutMs)
            ?: throw missingMarker("root '$ROOT_DESCRIPTION'", device)
        val surface = device.wait(Until.findObject(By.desc(SURFACE_DESCRIPTION)), timeoutMs)
            ?: throw missingMarker("surface '$SURFACE_DESCRIPTION'", device)
        val language = waitForLanguage(device, timeoutMs)
        val keyObjects = requiredKeys.associateWith { key ->
            device.wait(Until.findObject(By.desc(KEY_DESCRIPTION_PREFIX + key)), timeoutMs)
                ?: throw missingMarker("key '$key'", device)
        }
        val displayBounds = Rect(0, 0, device.displayWidth, device.displayHeight)
        val rootBounds = root.visibleBounds
        val surfaceBounds = surface.visibleBounds
        val keyBounds = keyObjects.mapValues { it.value.visibleBounds }

        validateBounds("root", rootBounds, displayBounds)
        validateBounds("surface", surfaceBounds, rootBounds)
        keyBounds.forEach { (key, bounds) -> validateBounds("key '$key'", bounds, surfaceBounds) }

        val window = KeyboardWindow(
            rootBounds = rootBounds,
            surfaceBounds = surfaceBounds,
            language = language,
            keyBounds = keyBounds,
        )
        Log.i(
            "E2E-PERF",
            "phase=keyboard_locator durationMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                "language=${language.name}",
        )
        return window
    }

    private fun waitForLanguage(device: UiDevice, timeoutMs: Long): Language {
        val english = By.desc(LANGUAGE_DESCRIPTION_PREFIX + Language.ENGLISH.name)
        val hebrew = By.desc(LANGUAGE_DESCRIPTION_PREFIX + Language.HEBREW.name)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (device.findObject(english) != null) return Language.ENGLISH
            if (device.findObject(hebrew) != null) return Language.HEBREW
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw missingMarker("language marker", device)
    }

    private fun validateBounds(name: String, bounds: Rect, container: Rect) {
        require(!bounds.isEmpty) { "Marked $name has empty bounds: $bounds" }
        require(container.contains(bounds)) {
            "Marked $name bounds $bounds are outside container $container"
        }
    }

    private fun missingMarker(name: String, device: UiDevice): IllegalStateException =
        IllegalStateException(missingMarkerMessage(name, dumpHierarchy(device)))

    private fun dumpHierarchy(device: UiDevice): String = ByteArrayOutputStream().let { output ->
        device.dumpWindowHierarchy(output)
        output.toString(StandardCharsets.UTF_8.name())
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
