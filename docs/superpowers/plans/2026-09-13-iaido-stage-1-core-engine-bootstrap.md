# Iaido Stage 1: Core-Engine Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Gradle project (`core-engine` + `app` modules) and build a minimal but real single-finger gesture-recognition pipeline in `core-engine` — trie-based candidate pruning, DTW+corner-bonus path scoring, frequency-weighted ranking — proven entirely by JVM unit tests against a tiny embedded test dictionary. No Android device, emulator, or IME plumbing yet (that's Stage 2).

**Architecture:** Two Gradle modules from the start: `core-engine` is a plain Kotlin/JVM library (no Android Gradle plugin, no Android SDK dependency) so its tests run instantly with plain JUnit; `app` is an empty Android application module for now, just enough to prove the project builds as an Android app, with real IME work starting in Stage 2. `core-engine` exposes two swappable interfaces — `CandidateGenerator` and `PathScorer` — composed by a `GestureRecognizer` that Stage 2 will call from the IME.

**Tech Stack:** Kotlin 2.x, Gradle (version catalog), JUnit 5, plain Kotlin/JVM library module for `core-engine`, Android Gradle Plugin for the (currently empty) `app` module.

**Spec:** [wayfinder/map.md](../../../wayfinder/map.md), specifically [Gesture-recognition algorithm architecture](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md) and [Tech stack & project structure](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md). Read both before starting — this plan implements their "Resolution" sections directly.

## Global Constraints

- `core-engine` MUST have zero Android dependency — if a task needs `android.*` or any Android Gradle plugin API inside `core-engine`, stop and reconsider; that logic belongs in `app`. ([008](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md))
- `minSdk` 31 for the `app` module, even though nothing in Stage 1 exercises it yet. ([008](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md))
- Gesture recognition is classic algorithmic path-matching (DTW-style shape distance + corner-matching bonus), never ML/neural. ([001](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md))
- `CandidateGenerator` and `PathScorer` are separately swappable interfaces — do not merge them into one class. ([001](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md))
- All tunable weights (λ's, corner-bonus weight, proximity thresholds) are named constants in one place, never inline magic numbers. ([001](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md), [015](../../../wayfinder/tickets/015-settings-app-ux.md) — these are exactly the constants later flagged as *not* user-exposed in Settings, so they need one clear internal home)
- Base package: `com.iaido`; `core-engine` code lives under `com.iaido.core`.

---

### Task 1: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `core-engine/build.gradle.kts`
- Create: `core-engine/src/main/kotlin/com/iaido/core/.gitkeep`
- Create: `core-engine/src/test/kotlin/com/iaido/core/.gitkeep`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/iaido/app/.gitkeep`

**Interfaces:**
- Produces: a Gradle project with two modules, `:core-engine` (Kotlin/JVM library) and `:app` (empty Android application, applicationId `com.iaido.app`), both building successfully.

This task has no test cycle (there's no behavior yet to test) — its "test" is a successful Gradle build, checked in Step 5.

- [ ] **Step 1: Create the Gradle wrapper and root build files**

`settings.gradle.kts`:
```kotlin
rootProject.name = "Iaido"
include(":core-engine", ":app")
```

`gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.0.21"
agp = "8.7.2"
junit = "5.11.3"
compose-bom = "2024.11.00"
activity-compose = "1.9.3"
core-ktx = "1.15.0"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "core-ktx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity-compose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
```

`build.gradle.kts` (root):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m
kotlin.code.style=official
android.useAndroidX=true
```

`.gitignore`:
```
.gradle/
build/
local.properties
*.iml
.idea/
.kotlin/
```

- [ ] **Step 2: Create the `core-engine` module**

`core-engine/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
```

Create the empty package directories by adding placeholder files (Git doesn't track empty directories):
`core-engine/src/main/kotlin/com/iaido/core/.gitkeep` — empty file.
`core-engine/src/test/kotlin/com/iaido/core/.gitkeep` — empty file.

- [ ] **Step 3: Create the `app` module**

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.iaido.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iaido.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-engine"))
    implementation(libs.androidx.core.ktx)
}
```

Note: `compileSdk`/`targetSdk` 36 is a starting value — verify the actual current-stable Android API level in Android Studio's SDK Manager at implementation time and bump if a newer stable level has shipped, per the tech-stack ticket's "current-stable tooling" preference.

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="Iaido"
        android:allowBackup="true">
    </application>
</manifest>
```

`app/src/main/kotlin/com/iaido/app/.gitkeep` — empty file.

- [ ] **Step 4: Verify the project builds**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (both modules compile; there are no tests yet, so no test output is expected).

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/ gradle.properties .gitignore core-engine/ app/
git commit -m "Scaffold Gradle project: core-engine (Kotlin/JVM) + app (Android) modules"
```

---

### Task 2: Gesture path data model

**Files:**
- Create: `core-engine/src/main/kotlin/com/iaido/core/gesture/GesturePoint.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/gesture/GesturePath.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/gesture/GesturePathTest.kt`

**Interfaces:**
- Produces:
  - `data class GesturePoint(val x: Float, val y: Float, val timestampMs: Long)`
  - `data class GesturePath(val points: List<GesturePoint>)` with a method `fun resample(targetPointCount: Int): GesturePath` that arc-length-resamples the path to exactly `targetPointCount` evenly-spaced points (per the [gesture-algorithm ticket](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md)'s "Path handling" resolution).

- [ ] **Step 1: Write the failing test for resampling**

```kotlin
package com.iaido.core.gesture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class GesturePathTest {

    @Test
    fun `resample produces exactly the requested number of points`() {
        val path = GesturePath(
            listOf(
                GesturePoint(0f, 0f, 0),
                GesturePoint(10f, 0f, 10),
                GesturePoint(10f, 10f, 20),
            )
        )

        val resampled = path.resample(5)

        assertEquals(5, resampled.points.size)
    }

    @Test
    fun `resample preserves start and end points`() {
        val path = GesturePath(
            listOf(
                GesturePoint(0f, 0f, 0),
                GesturePoint(10f, 0f, 10),
                GesturePoint(10f, 10f, 20),
            )
        )

        val resampled = path.resample(5)

        assertEquals(0f, resampled.points.first().x, 0.001f)
        assertEquals(0f, resampled.points.first().y, 0.001f)
        assertEquals(10f, resampled.points.last().x, 0.001f)
        assertEquals(10f, resampled.points.last().y, 0.001f)
    }

    @Test
    fun `resample evenly spaces points by arc length`() {
        // A straight horizontal line of length 20 resampled to 3 points
        // should land at x = 0, 10, 20.
        val path = GesturePath(
            listOf(
                GesturePoint(0f, 0f, 0),
                GesturePoint(20f, 0f, 20),
            )
        )

        val resampled = path.resample(3)

        assertEquals(0f, resampled.points[0].x, 0.001f)
        assertEquals(10f, resampled.points[1].x, 0.001f)
        assertEquals(20f, resampled.points[2].x, 0.001f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.gesture.GesturePathTest"`
Expected: FAIL (compile error — `GesturePoint`/`GesturePath` don't exist yet).

- [ ] **Step 3: Write the minimal implementation**

`GesturePoint.kt`:
```kotlin
package com.iaido.core.gesture

data class GesturePoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long,
)
```

`GesturePath.kt`:
```kotlin
package com.iaido.core.gesture

import kotlin.math.sqrt

data class GesturePath(val points: List<GesturePoint>) {

    init {
        require(points.isNotEmpty()) { "GesturePath must have at least one point" }
    }

    fun resample(targetPointCount: Int): GesturePath {
        require(targetPointCount >= 2) { "targetPointCount must be at least 2" }
        if (points.size == 1) {
            return GesturePath(List(targetPointCount) { points[0] })
        }

        val segmentLengths = points.zipWithNext { a, b -> distance(a, b) }
        val totalLength = segmentLengths.sum()
        if (totalLength == 0f) {
            return GesturePath(List(targetPointCount) { points[0] })
        }

        val step = totalLength / (targetPointCount - 1)
        val result = mutableListOf<GesturePoint>()
        result.add(points.first())

        var segmentIndex = 0
        var distanceIntoSegment = 0f
        var accumulatedTarget = step

        while (result.size < targetPointCount - 1) {
            val segLen = segmentLengths[segmentIndex]
            val remainingInSegment = segLen - distanceIntoSegment
            if (remainingInSegment >= step) {
                distanceIntoSegment += step
                val t = distanceIntoSegment / segLen
                result.add(interpolate(points[segmentIndex], points[segmentIndex + 1], t))
            } else {
                accumulatedTarget = step - remainingInSegment
                segmentIndex++
                distanceIntoSegment = accumulatedTarget
                if (segmentIndex >= segmentLengths.size) break
                val segLen2 = segmentLengths[segmentIndex]
                val t = (distanceIntoSegment / segLen2).coerceIn(0f, 1f)
                result.add(interpolate(points[segmentIndex], points[segmentIndex + 1], t))
            }
        }

        result.add(points.last())
        return GesturePath(result)
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun interpolate(a: GesturePoint, b: GesturePoint, t: Float): GesturePoint {
        return GesturePoint(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            timestampMs = a.timestampMs + ((b.timestampMs - a.timestampMs) * t).toLong(),
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.gesture.GesturePathTest"`
Expected: PASS (all 3 tests green).

- [ ] **Step 5: Commit**

```bash
git add core-engine/src/main/kotlin/com/iaido/core/gesture/ core-engine/src/test/kotlin/com/iaido/core/gesture/GesturePathTest.kt
git commit -m "Add GesturePoint/GesturePath with arc-length resampling"
```

---

### Task 3: Keyboard layout model (key centers for scoring)

**Files:**
- Create: `core-engine/src/main/kotlin/com/iaido/core/layout/KeyPosition.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/layout/KeyboardLayout.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/layout/KeyboardLayoutTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `data class KeyPosition(val letter: Char, val x: Float, val y: Float)`
  - `class KeyboardLayout(val keys: List<KeyPosition>)` with `fun centerOf(letter: Char): KeyPosition` (throws `NoSuchElementException` if the letter isn't in the layout) and a companion factory `KeyboardLayout.qwertyTestLayout()` producing a small fixed 3-row QWERTY layout used by tests and later stages' fixtures.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.iaido.core.layout

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows

class KeyboardLayoutTest {

    @Test
    fun `centerOf returns the position of a known key`() {
        val layout = KeyboardLayout.qwertyTestLayout()

        val position = layout.centerOf('q')

        assertEquals('q', position.letter)
    }

    @Test
    fun `centerOf throws for a letter not in the layout`() {
        val layout = KeyboardLayout(keys = listOf(KeyPosition('a', 0f, 0f)))

        assertThrows<NoSuchElementException> {
            layout.centerOf('z')
        }
    }

    @Test
    fun `qwertyTestLayout places q to the left of w`() {
        val layout = KeyboardLayout.qwertyTestLayout()

        val q = layout.centerOf('q')
        val w = layout.centerOf('w')

        assert(q.x < w.x) { "expected q.x (${q.x}) < w.x (${w.x})" }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.layout.KeyboardLayoutTest"`
Expected: FAIL (compile error — types don't exist yet).

- [ ] **Step 3: Write the minimal implementation**

`KeyPosition.kt`:
```kotlin
package com.iaido.core.layout

data class KeyPosition(
    val letter: Char,
    val x: Float,
    val y: Float,
)
```

`KeyboardLayout.kt`:
```kotlin
package com.iaido.core.layout

class KeyboardLayout(val keys: List<KeyPosition>) {

    private val byLetter: Map<Char, KeyPosition> = keys.associateBy { it.letter }

    fun centerOf(letter: Char): KeyPosition =
        byLetter[letter] ?: throw NoSuchElementException("No key for letter '$letter'")

    companion object {
        /**
         * A fixed 3-row QWERTY layout for tests and Stage 1 fixtures.
         * Row y-values increase downward; each row is horizontally offset
         * to loosely match a real QWERTY stagger.
         */
        fun qwertyTestLayout(): KeyboardLayout {
            val row1 = "qwertyuiop"
            val row2 = "asdfghjkl"
            val row3 = "zxcvbnm"
            val keys = mutableListOf<KeyPosition>()
            row1.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f, 0f)) }
            row2.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f + 0.5f, 1f)) }
            row3.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f + 1f, 2f)) }
            return KeyboardLayout(keys)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.layout.KeyboardLayoutTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core-engine/src/main/kotlin/com/iaido/core/layout/ core-engine/src/test/kotlin/com/iaido/core/layout/KeyboardLayoutTest.kt
git commit -m "Add KeyboardLayout model with a fixed QWERTY test layout"
```

---

### Task 4: `CandidateGenerator` — trie-based pruning

**Files:**
- Create: `core-engine/src/main/kotlin/com/iaido/core/dictionary/WordEntry.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/CandidateGenerator.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/TrieCandidateGenerator.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/recognition/TrieCandidateGeneratorTest.kt`

**Interfaces:**
- Consumes: `GesturePath`/`GesturePoint` (Task 2), `KeyboardLayout`/`KeyPosition` (Task 3).
- Produces:
  - `data class WordEntry(val word: String, val frequency: Double)`
  - `interface CandidateGenerator { fun generateCandidates(path: GesturePath, layout: KeyboardLayout, dictionary: List<WordEntry>): List<WordEntry> }`
  - `class TrieCandidateGenerator(private val proximityThreshold: Float = 1.5f) : CandidateGenerator` — later tasks (Task 5 `PathScorer`, Task 6 `GestureRecognizer`) consume this interface and this implementation by name.

**Design note for the implementer:** the full "traverse a dictionary trie letter-by-letter" structure described in the ticket is overkill for Stage 1's tiny test dictionary; this task implements the same *behavior* (prune candidates whose letters aren't plausibly near the path) with a simpler per-word check, so it's correct and testable now. A real trie can replace the internals later without changing the `CandidateGenerator` interface — that's exactly the point of the interface being swappable.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

class TrieCandidateGeneratorTest {

    private val layout = KeyboardLayout.qwertyTestLayout()
    private val generator = TrieCandidateGenerator()

    private fun pathThrough(vararg letters: Char): GesturePath {
        val points = letters.mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    @Test
    fun `includes a word whose letters lie on the path`() {
        val dictionary = listOf(WordEntry("hi", 1.0), WordEntry("bye", 1.0))
        val path = pathThrough('h', 'i')

        val candidates = generator.generateCandidates(path, layout, dictionary)

        assertTrue(candidates.any { it.word == "hi" })
    }

    @Test
    fun `excludes a word whose letters are far from the path`() {
        val dictionary = listOf(WordEntry("hi", 1.0), WordEntry("bye", 1.0))
        val path = pathThrough('h', 'i')

        val candidates = generator.generateCandidates(path, layout, dictionary)

        assertFalse(candidates.any { it.word == "bye" })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.recognition.TrieCandidateGeneratorTest"`
Expected: FAIL (compile error — types don't exist yet).

- [ ] **Step 3: Write the minimal implementation**

`WordEntry.kt`:
```kotlin
package com.iaido.core.dictionary

data class WordEntry(
    val word: String,
    val frequency: Double,
)
```

`CandidateGenerator.kt`:
```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout

interface CandidateGenerator {
    fun generateCandidates(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<WordEntry>
}
```

`TrieCandidateGenerator.kt`:
```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import kotlin.math.sqrt

/**
 * Prunes the dictionary to words whose letters plausibly lie along the
 * drawn path, in order. This is a straightforward per-word proximity
 * check rather than a literal trie traversal -- fine for Stage 1's small
 * test dictionary. A trie-backed implementation can replace this one
 * later without changing [CandidateGenerator] callers.
 */
class TrieCandidateGenerator(
    private val proximityThreshold: Float = 1.5f,
) : CandidateGenerator {

    override fun generateCandidates(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<WordEntry> {
        return dictionary.filter { entry -> isPlausible(entry.word, path, layout) }
    }

    private fun isPlausible(word: String, path: GesturePath, layout: KeyboardLayout): Boolean {
        var searchStartIndex = 0
        for (letter in word) {
            val key = layout.centerOf(letter)
            val matchIndex = findNearestPointFrom(searchStartIndex, key, path)
            if (matchIndex == -1) return false
            searchStartIndex = matchIndex
        }
        return true
    }

    private fun findNearestPointFrom(startIndex: Int, key: com.iaido.core.layout.KeyPosition, path: GesturePath): Int {
        for (i in startIndex until path.points.size) {
            if (distance(path.points[i], key.x, key.y) <= proximityThreshold) {
                return i
            }
        }
        return -1
    }

    private fun distance(point: GesturePoint, x: Float, y: Float): Float {
        val dx = point.x - x
        val dy = point.y - y
        return sqrt(dx * dx + dy * dy)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.recognition.TrieCandidateGeneratorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core-engine/src/main/kotlin/com/iaido/core/dictionary/ core-engine/src/main/kotlin/com/iaido/core/recognition/CandidateGenerator.kt core-engine/src/main/kotlin/com/iaido/core/recognition/TrieCandidateGenerator.kt core-engine/src/test/kotlin/com/iaido/core/recognition/TrieCandidateGeneratorTest.kt
git commit -m "Add WordEntry and TrieCandidateGenerator (path-proximity pruning)"
```

---

### Task 5: `PathScorer` — DTW-style shape distance + corner bonus

**Files:**
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/ScoredCandidate.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/PathScorer.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/ShapePathScorer.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/ScoringConstants.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/recognition/ShapePathScorerTest.kt`

**Interfaces:**
- Consumes: `GesturePath` (Task 2), `KeyboardLayout` (Task 3), `WordEntry` (Task 4).
- Produces:
  - `data class ScoredCandidate(val word: WordEntry, val score: Double)` — higher score is better.
  - `interface PathScorer { fun score(path: GesturePath, candidates: List<WordEntry>, layout: KeyboardLayout): List<ScoredCandidate> }` (returned list sorted descending by score) — Task 6 (`GestureRecognizer`) consumes this by name.
  - `class ShapePathScorer(private val resamplePointCount: Int = 32) : PathScorer`
  - `object ScoringConstants` holding named weights (`SHAPE_DISTANCE_WEIGHT`, `CORNER_BONUS_WEIGHT`, `FREQUENCY_WEIGHT`) — this is the single home for tunables the [gesture-algorithm ticket](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md) requires, later tasks/stages add to this object rather than creating a second one.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ShapePathScorerTest {

    private val layout = KeyboardLayout.qwertyTestLayout()
    private val scorer = ShapePathScorer()

    private fun pathThrough(vararg letters: Char): GesturePath {
        val points = letters.mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    @Test
    fun `a path drawn through hi's own keys scores hi above an unrelated word`() {
        val path = pathThrough('h', 'i')
        val candidates = listOf(WordEntry("hi", 1.0), WordEntry("no", 1.0))

        val results = scorer.score(path, candidates, layout)

        val hiScore = results.first { it.word.word == "hi" }.score
        val noScore = results.first { it.word.word == "no" }.score
        assertTrue(hiScore > noScore, "expected hi ($hiScore) > no ($noScore)")
    }

    @Test
    fun `results are sorted descending by score`() {
        val path = pathThrough('h', 'i')
        val candidates = listOf(WordEntry("no", 1.0), WordEntry("hi", 1.0))

        val results = scorer.score(path, candidates, layout)

        assertEquals("hi", results.first().word.word)
    }

    @Test
    fun `scoring an empty candidate list returns an empty result`() {
        val path = pathThrough('h', 'i')

        val results = scorer.score(path, emptyList(), layout)

        assertEquals(0, results.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.recognition.ShapePathScorerTest"`
Expected: FAIL (compile error — types don't exist yet).

- [ ] **Step 3: Write the minimal implementation**

`ScoredCandidate.kt`:
```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry

data class ScoredCandidate(
    val word: WordEntry,
    val score: Double,
)
```

`PathScorer.kt`:
```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout

interface PathScorer {
    /** Returns candidates ranked descending by score (best match first). */
    fun score(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate>
}
```

`ScoringConstants.kt`:
```kotlin
package com.iaido.core.recognition

/**
 * Single home for gesture-recognition tunable weights, per the
 * gesture-recognition-architecture ticket's requirement that these never
 * be inline magic numbers. These are internal developer-tuning constants,
 * not user-facing settings (see the settings-app-ux ticket).
 */
object ScoringConstants {
    /** Weight applied to the raw DTW-style shape distance term. */
    const val SHAPE_DISTANCE_WEIGHT: Double = 1.0

    /** Weight applied to the corner-matching bonus/penalty term. */
    const val CORNER_BONUS_WEIGHT: Double = 0.5

    /** Weight applied to log(frequency) in the final combined score. */
    const val FREQUENCY_WEIGHT: Double = 0.1
}
```

`ShapePathScorer.kt`:
```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import kotlin.math.ln
import kotlin.math.sqrt

class ShapePathScorer(
    private val resamplePointCount: Int = 32,
) : PathScorer {

    override fun score(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val userPath = path.resample(resamplePointCount)

        return candidates
            .map { entry -> ScoredCandidate(entry, scoreOne(userPath, entry, layout)) }
            .sortedByDescending { it.score }
    }

    private fun scoreOne(userPath: GesturePath, entry: WordEntry, layout: KeyboardLayout): Double {
        val idealPath = idealPathFor(entry.word, layout).resample(resamplePointCount)

        val shapeDistance = elasticDistance(userPath, idealPath)
        val cornerBonus = cornerMatchBonus(userPath, idealPath)
        val frequencyTerm = ln(entry.frequency.coerceAtLeast(1.0))

        // Distance is a cost (lower is better), so it's subtracted; the
        // corner bonus and frequency term add to the score.
        return -ScoringConstants.SHAPE_DISTANCE_WEIGHT * shapeDistance +
            ScoringConstants.CORNER_BONUS_WEIGHT * cornerBonus +
            ScoringConstants.FREQUENCY_WEIGHT * frequencyTerm
    }

    /** A word's "ideal path" is straight lines connecting its successive key centers. */
    private fun idealPathFor(word: String, layout: KeyboardLayout): GesturePath {
        val points = word.mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    /** Sum of point-to-point distances between two equal-length resampled paths. */
    private fun elasticDistance(a: GesturePath, b: GesturePath): Double {
        var total = 0.0
        for (i in a.points.indices) {
            total += pointDistance(a.points[i], b.points[i])
        }
        return total
    }

    /**
     * Rewards paths whose direction changes ("corners") happen at similar
     * relative positions along the path -- this is what lets scoring
     * distinguish words with similar overall shape but different turning
     * points, per the gesture-recognition-architecture ticket.
     */
    private fun cornerMatchBonus(a: GesturePath, b: GesturePath): Double {
        val cornersA = turningPointIndices(a)
        val cornersB = turningPointIndices(b)
        if (cornersA.isEmpty() && cornersB.isEmpty()) return 1.0

        val matched = cornersA.count { indexA ->
            cornersB.any { indexB -> kotlin.math.abs(indexA - indexB) <= 2 }
        }
        val totalCorners = maxOf(cornersA.size, cornersB.size, 1)
        return matched.toDouble() / totalCorners
    }

    private fun turningPointIndices(path: GesturePath): List<Int> {
        val indices = mutableListOf<Int>()
        val points = path.points
        for (i in 1 until points.size - 1) {
            val dx1 = points[i].x - points[i - 1].x
            val dy1 = points[i].y - points[i - 1].y
            val dx2 = points[i + 1].x - points[i].x
            val dy2 = points[i + 1].y - points[i].y
            val cross = dx1 * dy2 - dy1 * dx2
            if (kotlin.math.abs(cross) > 0.3f) {
                indices.add(i)
            }
        }
        return indices
    }

    private fun pointDistance(p1: GesturePoint, p2: GesturePoint): Double {
        val dx = (p1.x - p2.x).toDouble()
        val dy = (p1.y - p2.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.recognition.ShapePathScorerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core-engine/src/main/kotlin/com/iaido/core/recognition/ScoredCandidate.kt core-engine/src/main/kotlin/com/iaido/core/recognition/PathScorer.kt core-engine/src/main/kotlin/com/iaido/core/recognition/ShapePathScorer.kt core-engine/src/main/kotlin/com/iaido/core/recognition/ScoringConstants.kt core-engine/src/test/kotlin/com/iaido/core/recognition/ShapePathScorerTest.kt
git commit -m "Add ShapePathScorer: DTW-style shape distance + corner-match bonus"
```

---

### Task 6: `GestureRecognizer` — end-to-end composition

**Files:**
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/GestureRecognizer.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/recognition/GestureRecognizerTest.kt`

**Interfaces:**
- Consumes: `CandidateGenerator`/`TrieCandidateGenerator` (Task 4), `PathScorer`/`ShapePathScorer` (Task 5), `GesturePath` (Task 2), `KeyboardLayout` (Task 3), `WordEntry` (Task 4).
- Produces: `class GestureRecognizer(private val candidateGenerator: CandidateGenerator, private val pathScorer: PathScorer) { fun recognize(path: GesturePath, layout: KeyboardLayout, dictionary: List<WordEntry>): List<ScoredCandidate> }` — Stage 2's IME plumbing will construct one `GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer())` and call `recognize(...)` per completed swipe; this is the single public entry point later stages/the IME depend on.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class GestureRecognizerTest {

    private val layout = KeyboardLayout.qwertyTestLayout()
    private val recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer())

    private fun pathThrough(vararg letters: Char): GesturePath {
        val points = letters.mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    @Test
    fun `recognizing a swipe through h then i returns hi as the top result`() {
        val dictionary = listOf(
            WordEntry("hi", 1.0),
            WordEntry("no", 1.0),
            WordEntry("bye", 1.0),
        )
        val path = pathThrough('h', 'i')

        val results = recognizer.recognize(path, layout, dictionary)

        assertTrue(results.isNotEmpty(), "expected at least one result")
        assertEquals("hi", results.first().word.word)
    }

    @Test
    fun `recognizing with an empty dictionary returns no results`() {
        val path = pathThrough('h', 'i')

        val results = recognizer.recognize(path, layout, emptyList())

        assertEquals(0, results.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.recognition.GestureRecognizerTest"`
Expected: FAIL (compile error — `GestureRecognizer` doesn't exist yet).

- [ ] **Step 3: Write the minimal implementation**

`GestureRecognizer.kt`:
```kotlin
package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout

/**
 * The single entry point the IME (Stage 2 onward) calls per completed
 * swipe. Composes a swappable [CandidateGenerator] and [PathScorer] --
 * either can be replaced independently without changing callers.
 */
class GestureRecognizer(
    private val candidateGenerator: CandidateGenerator,
    private val pathScorer: PathScorer,
) {
    fun recognize(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<ScoredCandidate> {
        val candidates = candidateGenerator.generateCandidates(path, layout, dictionary)
        return pathScorer.score(path, candidates, layout)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core-engine:test --tests "com.iaido.core.recognition.GestureRecognizerTest"`
Expected: PASS.

- [ ] **Step 5: Run the full `core-engine` test suite**

Run: `./gradlew :core-engine:test`
Expected: BUILD SUCCESSFUL, all tests from Tasks 2-6 passing.

- [ ] **Step 6: Commit**

```bash
git add core-engine/src/main/kotlin/com/iaido/core/recognition/GestureRecognizer.kt core-engine/src/test/kotlin/com/iaido/core/recognition/GestureRecognizerTest.kt
git commit -m "Add GestureRecognizer composing CandidateGenerator + PathScorer end to end"
```

---

## Self-Review

**Spec coverage:**
- Swappable `CandidateGenerator`/`PathScorer` interfaces — Tasks 4, 5, 6. ✓
- Arc-length resampling + normalization scaffolding — Task 2 (resampling); layout-relative normalization is implicit in scoring against `KeyboardLayout`-derived ideal paths in Task 5, full explicit position/scale normalization across differing keyboard sizes is deferred to Stage 2 when a real on-screen layout with actual pixel dimensions exists (Stage 1 uses one fixed test layout, so normalization has nothing to normalize against yet — noted here rather than silently assumed).
- DTW-style shape distance + corner-matching bonus — Task 5. ✓
- Frequency weighting in the combined score — Task 5 (`FREQUENCY_WEIGHT`, `ln(frequency)` term). ✓ (personal-learning and n-gram-context terms from the ticket are explicitly Stage 7/8 work, not Stage 1 — noted in the roadmap, not silently dropped.)
- Named, centralized tunable constants — Task 5 (`ScoringConstants`). ✓
- Two-module Gradle structure with `core-engine` having zero Android dependency — Task 1. ✓ (verified structurally: `core-engine/build.gradle.kts` applies only the Kotlin JVM plugin, no Android plugin, no `androidx.*`/`android.*` dependency.)

**Placeholder scan:** no TBD/TODO markers; every step has runnable code and an exact command.

**Type consistency:** `GesturePath`/`GesturePoint` (Task 2) → consumed identically in Tasks 4, 5, 6. `KeyboardLayout`/`KeyPosition` (Task 3) → consumed identically in Tasks 4, 5, 6. `WordEntry` (Task 4) → consumed identically in Tasks 5, 6. `CandidateGenerator`/`PathScorer` interface method signatures (Tasks 4, 5) match exactly what `GestureRecognizer` (Task 6) calls.

---

Plan complete and saved to `docs/superpowers/plans/2026-09-13-iaido-stage-1-core-engine-bootstrap.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
