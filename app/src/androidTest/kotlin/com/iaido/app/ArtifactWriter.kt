package com.iaido.app

import android.os.Build
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

object ArtifactWriter {
    fun captureScreenshot(name: String, device: UiDevice): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir("ime-e2e"), "checkpoints").apply { mkdirs() }
        return File(directory, safeName(name) + ".png").also(device::takeScreenshot)
    }

    fun capture(
        testName: String,
        device: UiDevice,
        scenarioState: ImeScenarioState?,
        failure: Throwable,
    ): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir("ime-e2e"), safeName(testName)).apply { mkdirs() }
        device.takeScreenshot(File(directory, "screen.png"))
        device.dumpWindowHierarchy(File(directory, "windows.xml"))
        write(directory, "ime.txt", shell("dumpsys input_method"))
        write(directory, "focused-window.txt", shell("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'"))
        write(directory, "logcat.txt", shell("logcat -d -v threadtime Iaido:D AndroidRuntime:E *:S"))
        write(directory, "metadata.json", jsonObject(
            "package" to context.packageName,
            "api" to Build.VERSION.SDK_INT.toString(),
            "test" to testName,
            "failure" to (failure.stackTraceToString()),
        ))
        write(directory, "state.json", scenarioState?.let(::stateJson).orEmpty())
        write(directory, "trace.jsonl", scenarioState?.trace.orEmpty().joinToString("\n", transform = ::eventJson))
        return directory
    }

    private fun stateJson(state: ImeScenarioState): String = jsonObject(
        "expectedText" to state.expectedText,
        "expectedSelection" to state.expectedSelection.toString(),
        "expectedIme" to state.expectedIme,
        "expectedLanguage" to state.expectedLanguage.name,
    )

    private fun eventJson(event: ImeScenarioEvent): String = jsonObject(
        "index" to event.index.toString(),
        "action" to event.action,
        "expectedText" to event.expectedText,
        "observedText" to event.observedText,
        "expectedSelection" to event.expectedSelection.toString(),
        "observedSelection" to "${event.observedSelection.first}:${event.observedSelection.last}",
        "expectedIme" to event.expectedIme,
        "observedIme" to event.observedIme,
        "expectedLanguage" to event.expectedLanguage.name,
        "observedLanguage" to event.observedLanguage.name,
        "gestureSeed" to (event.gestureSeed?.toString() ?: ""),
        "pointerEvents" to event.pointerEvents.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
            transform = ::pointerEventJson,
        ),
    )

    private fun pointerEventJson(event: InjectedPointerEvent): String = jsonObject(
        "action" to event.action.toString(),
        "actionIndex" to event.actionIndex.toString(),
        "eventTimeMs" to event.eventTimeMs.toString(),
        "pointers" to event.pointers.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
            transform = { pointer -> jsonObject(
                "id" to pointer.id.toString(),
                "x" to pointer.x.toString(),
                "y" to pointer.y.toString(),
            ) },
        ),
    )

    private fun jsonObject(vararg fields: Pair<String, String>): String = fields.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ",",
    ) { (key, value) -> "\"${escape(key)}\":\"${escape(value)}\"" }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    private fun write(directory: File, name: String, content: String) {
        File(directory, name).writeText(content)
    }

    private fun shell(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        descriptor.use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
    }.getOrElse { "command failed: $command\n${it.stackTraceToString()}" }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
}
