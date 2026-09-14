package com.ninjakeys.app

import android.graphics.Point
import android.graphics.Rect
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ninjakeys.core.language.Language
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
    const val ROOT_DESCRIPTION = "NinjaKeys keyboard root"
    const val SURFACE_DESCRIPTION = "NinjaKeys swipe surface"
    const val LANGUAGE_DESCRIPTION_PREFIX = "NinjaKeys language "
    const val KEY_DESCRIPTION_PREFIX = "NinjaKeys key "

    private val requiredKeys = listOf("globe", "space", "backspace")

    fun locate(device: UiDevice, timeoutMs: Long = 5_000L): KeyboardWindow {
        if (!device.wait(Until.hasObject(By.desc(ROOT_DESCRIPTION)), timeoutMs)) {
            throw missingMarker("root '$ROOT_DESCRIPTION'", device)
        }

        val root = device.findObject(By.desc(ROOT_DESCRIPTION))
            ?: throw missingMarker("root '$ROOT_DESCRIPTION'", device)
        val surface = device.findObject(By.desc(SURFACE_DESCRIPTION))
            ?: throw missingMarker("surface '$SURFACE_DESCRIPTION'", device)
        val language = waitForLanguage(device, timeoutMs)
        val keyObjects = requiredKeys.associateWith { key ->
            device.findObject(By.desc(KEY_DESCRIPTION_PREFIX + key))
                ?: throw missingMarker("key '$key'", device)
        }
        val displayBounds = Rect(0, 0, device.displayWidth, device.displayHeight)
        val rootBounds = root.visibleBounds
        val surfaceBounds = surface.visibleBounds
        val keyBounds = keyObjects.mapValues { it.value.visibleBounds }

        validateBounds("root", rootBounds, displayBounds)
        validateBounds("surface", surfaceBounds, rootBounds)
        keyBounds.forEach { (key, bounds) -> validateBounds("key '$key'", bounds, surfaceBounds) }

        return KeyboardWindow(
            rootBounds = rootBounds,
            surfaceBounds = surfaceBounds,
            language = language,
            keyBounds = keyBounds,
        )
    }

    private fun waitForLanguage(device: UiDevice, timeoutMs: Long): Language {
        val english = By.desc(LANGUAGE_DESCRIPTION_PREFIX + Language.ENGLISH.name)
        val hebrew = By.desc(LANGUAGE_DESCRIPTION_PREFIX + Language.HEBREW.name)
        if (device.wait(Until.hasObject(english), timeoutMs)) return Language.ENGLISH
        if (device.wait(Until.hasObject(hebrew), timeoutMs)) return Language.HEBREW
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
