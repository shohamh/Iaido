package com.iaido.app

import android.graphics.Rect
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.language.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardWindowLocatorTest {
    @Test
    fun keyCenterIsRelativeToMarkedBounds() {
        assertEquals(150, centerOf(Rect(100, 200, 201, 301)).x)
        assertEquals(250, centerOf(Rect(100, 200, 201, 301)).y)
    }

    @Test
    fun gesturePathUsesVisibleLetterKeyBoundsInsteadOfAnInferredRowOffset() {
        val window = KeyboardWindow(
            rootBounds = Rect(0, 0, 1_080, 2_400),
            surfaceBounds = Rect(11, 1_744, 1_069, 2_274),
            language = Language.ENGLISH,
            keyBounds = mapOf(
                "t" to Rect(439, 1_850, 537, 1_956),
                "h" to Rect(598, 1_956, 696, 2_062),
                "e" to Rect(227, 1_850, 325, 1_956),
                "r" to Rect(333, 1_850, 431, 1_956),
            ),
        )

        val path = window.pathThroughVisibleKeys("there")

        assertEquals(
            listOf(
                GesturePoint(477f, 159f, 0L),
                GesturePoint(636f, 265f, 10L),
                GesturePoint(265f, 159f, 20L),
                GesturePoint(371f, 159f, 30L),
                GesturePoint(265f, 159f, 40L),
            ),
            path.points,
        )
        path.points.zip("there".toList()).forEach { (point, letter) ->
            val keyCenter = window.keyCenter(letter.toString())
            assertEquals(keyCenter.x.toFloat(), window.surfaceBounds.left + point.x, 0.001f)
            assertEquals(keyCenter.y.toFloat(), window.surfaceBounds.top + point.y, 0.001f)
        }
    }

    @Test
    fun navigationSafeBottomLeavesInsetBelowKeyboardSurface() {
        assertEquals(1_872, navigationSafeBottom(Rect(0, 0, 1_080, 1_920), 48))
        assertEquals(1_920, navigationSafeBottom(Rect(0, 0, 1_080, 1_920), 0))
    }

    @Test
    fun negativeNavigationInsetCannotMoveSafeBottomBelowDisplayTop() {
        assertEquals(1_920, navigationSafeBottom(Rect(0, 100, 1_080, 1_920), -50))
        assertEquals(100, navigationSafeBottom(Rect(0, 100, 1_080, 1_920), 5_000))
    }

    @Test
    fun missingMarkerMessageIncludesMarkerAndHierarchy() {
        val message = missingMarkerMessage("key 'space'", "<node text=\"editor\"/>")
        assertTrue(message.contains("key 'space'"))
        assertTrue(message.contains("<node text=\"editor\"/>") )
    }

    @Test
    fun parsesKeyboardGeometryFromOneHierarchySnapshotAndUsesExpectedLanguage() {
        val hierarchy = """
            <hierarchy rotation="0">
              <node content-desc="Iaido keyboard root" bounds="[0,1357][1080,2400]">
                <node content-desc="Iaido language ENGLISH" bounds="[0,1357][3,1360]" />
                <node content-desc="Iaido swipe surface" bounds="[11,1744][1069,2274]">
                  <node content-desc="Iaido key backspace" bounds="[920,2062][1069,2168]" />
                  <node content-desc="Iaido key globe" bounds="[11,2168][113,2274]" />
                  <node content-desc="Iaido key space" bounds="[231,2168][847,2274]" />
                  <node content-desc="Iaido key enter" bounds="[966,2168][1069,2274]" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        val snapshot = KeyboardWindowLocator.parseKeyboardWindowHierarchySnapshot(
            hierarchy = hierarchy,
            expectedLanguage = Language.HEBREW,
        )

        assertEquals(Language.HEBREW, snapshot?.language)
        assertEquals(Rect(0, 1357, 1080, 2400), snapshot?.rootBounds)
        assertEquals(Rect(11, 1744, 1069, 2274), snapshot?.surfaceBounds)
        assertEquals(Rect(11, 2168, 113, 2274), snapshot?.keyBounds?.get("globe"))
        assertEquals(Rect(231, 2168, 847, 2274), snapshot?.keyBounds?.get("space"))
        assertEquals(Rect(966, 2168, 1069, 2274), snapshot?.keyBounds?.get("enter"))
        assertEquals(Rect(920, 2062, 1069, 2168), snapshot?.keyBounds?.get("backspace"))
    }

    @Test
    fun rejectsTransientKeyBoundsOutsideTheMarkedSurface() {
        val staleWindow = KeyboardWindow(
            rootBounds = Rect(0, 0, 1_080, 2_400),
            surfaceBounds = Rect(11, 1_618, 1_069, 2_148),
            language = Language.ENGLISH,
            keyBounds = mapOf(
                "comma" to Rect(121, 2_168, 223, 2_274),
                "space" to Rect(231, 2_168, 847, 2_274),
            ),
        )

        val failure = runCatching {
            KeyboardWindowLocator.validateSnapshot(staleWindow, Rect(0, 0, 1_080, 2_400))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("key 'comma'"))
    }

    @Test
    fun repairsStaleSurfaceAndKeyBoundsFromRenderedTextLabels() {
        val hierarchy = """
            <hierarchy rotation="0">
              <node content-desc="Iaido keyboard root" bounds="[0,1357][1080,2274]">
                <node content-desc="Iaido swipe surface" bounds="[11,1618][1069,2148]">
                  <node content-desc="Iaido key digit-1" class="android.view.View" bounds="[11,1618][109,1724]">
                    <node text="1" class="android.widget.TextView" bounds="[48,1766][73,1829]" />
                  </node>
                  <node content-desc="Iaido key q" class="android.view.View" bounds="[15,1724][113,1830]">
                    <node text="q" class="android.widget.TextView" bounds="[52,1872][77,1935]" />
                  </node>
                  <node content-desc="Iaido key backspace" bounds="[920,2062][1069,2168]" />
                  <node content-desc="Iaido key globe" bounds="[11,2168][113,2274]" />
                  <node content-desc="Iaido key space" bounds="[231,2168][847,2274]">
                    <node text="EN US" class="android.widget.TextView" bounds="[450,2189][628,2253]" />
                  </node>
                  <node content-desc="Iaido key enter" bounds="[966,2168][1069,2274]" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        val snapshot = KeyboardWindowLocator.parseKeyboardWindowHierarchySnapshot(
            hierarchy = hierarchy,
            expectedLanguage = Language.ENGLISH,
        )

        assertEquals(Rect(11, 1744, 1069, 2274), snapshot?.surfaceBounds)
        assertEquals(Rect(11, 1744, 109, 1850), snapshot?.keyBounds?.get("digit-1"))
        assertEquals(Rect(15, 1850, 113, 1956), snapshot?.keyBounds?.get("q"))
        KeyboardWindowLocator.validateSnapshot(snapshot!!, Rect(0, 0, 1080, 2400))
    }
}
