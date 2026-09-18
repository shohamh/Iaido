package com.iaido.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.datastore.preferences.core.edit
import com.iaido.core.typing.SpacingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ImeSystemController(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val automation: UiAutomation,
) {
    private val packageName = instrumentation.targetContext.packageName

    val iaidoImeId: String = ComponentName(packageName, "$packageName.IaidoInputMethodService").flattenToShortString()
    val referenceImeId: String = ComponentName(packageName, "$packageName.ReferenceInputMethodService").flattenToShortString()

    fun launchHost(autoSpaceFixture: String? = null) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val component = ComponentName(packageName, "$packageName.ImeTestHostActivity").flattenToShortString()
        val fixtureArgument = autoSpaceFixture?.let { " --es ${DebugAutoSpaceFixtures.EXTRA_FIXTURE} $it" }.orEmpty()
        repeat(HOST_LAUNCH_ATTEMPTS) { attempt ->
            shell("am start -n $component -f 0x14000000$fixtureArgument")
            if (device.wait(Until.hasObject(By.res("$packageName:id/ime_test_editor")), DEFAULT_TIMEOUT_MS)) {
                logPerf(
                    "host_launch",
                    startedAtMs,
                    "fixture=${autoSpaceFixture ?: "none"} attempt=${attempt + 1}",
                )
                return
            }
            if (attempt + 1 < HOST_LAUNCH_ATTEMPTS) {
                shell("am force-stop $packageName")
            }
        }
        error("IME test host did not launch after $HOST_LAUNCH_ATTEMPTS attempts; focused=${device.currentPackageName}")
    }

    fun enableAndSelect(imeId: String) {
        val startedAtMs = SystemClock.elapsedRealtime()
        shell("ime enable $imeId")
        shell("ime set $imeId")
        check(selectedInputMethodId() == imeId) {
            "IME '$imeId' was not selected; default=${selectedInputMethodId()}"
        }
        logPerf("ime_select", startedAtMs, "ime=$imeId")
    }

    fun ensureHostVisible(autoSpaceFixture: String? = null) {
        val editorSelector = By.res("$packageName:id/ime_test_editor")
        if (device.findObject(editorSelector) != null) return
        launchHost(autoSpaceFixture)
    }

    fun waitForSpacingMode(mode: SpacingMode) {
        val preferences = instrumentation.targetContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, Context.MODE_PRIVATE)
        val deadline = SystemClock.elapsedRealtime() + DEFAULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (preferences.getString(DebugAutoSpaceFixtures.ACTIVE_SPACING_MODE_KEY, null) == mode.name) {
                return
            }
            SystemClock.sleep(25L)
        }
        check(false) {
            "Spacing mode '$mode' did not reach the active IME"
        }
    }

    fun waitForRuntimeReady(minimumRevision: Long = 0L): Long {
        val preferences = instrumentation.targetContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, Context.MODE_PRIVATE)
        val deadline = SystemClock.elapsedRealtime() + DEFAULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val revision = preferences.getLong(DebugAutoSpaceFixtures.RUNTIME_READY_REVISION_KEY, -1L)
            if (revision >= minimumRevision && revision >= 0L) return revision
            SystemClock.sleep(25L)
        }
        error("IME runtime did not publish ready revision >= $minimumRevision")
    }

    fun setAutoSpaceFixture(autoSpaceFixture: String?) {
        instrumentation.targetContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (autoSpaceFixture == null) remove(DebugAutoSpaceFixtures.FIXTURE_KEY)
                else putString(DebugAutoSpaceFixtures.FIXTURE_KEY, autoSpaceFixture)
            }
            .commit()
    }

    /** Restores the mode expected by ordinary typing scenarios without relaunching the host. */
    fun ensureManualSpacingMode(): Boolean = runBlocking {
        val startedAtMs = SystemClock.elapsedRealtime()
        val context = instrumentation.targetContext
        val manualValue = spacingModeStoredValue(SpacingMode.MANUAL)
        val currentValue = context.settingsStore.data.first()[spacingModeKey]
        if (currentValue == manualValue) {
            logPerf("settings_reset", startedAtMs, "changed=false")
            return@runBlocking false
        }
        context.settingsStore.edit { preferences -> preferences[spacingModeKey] = manualValue }
        logPerf("settings_reset", startedAtMs, "changed=true")
        true
    }

    fun ensureSelected(imeId: String) {
        if (selectedInputMethodId() != imeId) enableAndSelect(imeId)
    }

    fun waitForImeVisible(imeId: String) {
        val startedAtMs = SystemClock.elapsedRealtime()
        if (device.findObject(markerFor(imeId)) != null) {
            logPerf("ime_visible", startedAtMs, "ime=$imeId fast=true")
            return
        }
        if (device.wait(Until.hasObject(markerFor(imeId)), IME_HANDOFF_TIMEOUT_MS)) {
            logPerf("ime_visible", startedAtMs, "ime=$imeId fast=false retry=false")
            return
        }
        // Android can accept `ime set` before the focused editor has completed its input
        // connection. Re-selecting after focus repairs that race without slowing healthy runs.
        shell("ime set $imeId")
        refocusEditor()
        check(device.wait(Until.hasObject(markerFor(imeId)), DEFAULT_TIMEOUT_MS)) {
            "IME '$imeId' did not become visible. default=${selectedInputMethodId()}\n" +
                "input_method=${shell("dumpsys input_method") }"
        }
        logPerf("ime_visible", startedAtMs, "ime=$imeId fast=false retry=true")
    }

    fun selectedInputMethodId(): String = shell("settings get secure ${Settings.Secure.DEFAULT_INPUT_METHOD}")
        .lineSequence()
        .lastOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

    fun hideKeyboard() {
        device.pressBack()
    }

    private fun refocusEditor() {
        val editor = device.findObject(By.res("$packageName:id/ime_test_editor")) ?: return
        val bounds = editor.visibleBounds
        device.click((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
    }

    private fun markerFor(imeId: String) = if (imeId == referenceImeId) {
        By.desc("Iaido reference keyboard")
    } else {
        By.desc(KeyboardWindowLocator.ROOT_DESCRIPTION)
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun logPerf(phase: String, startedAtMs: Long, details: String = "") {
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        Log.i("E2E-PERF", "phase=$phase durationMs=$durationMs $details".trim())
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val IME_HANDOFF_TIMEOUT_MS = 1_500L
        private const val HOST_LAUNCH_ATTEMPTS = 2
    }
}
