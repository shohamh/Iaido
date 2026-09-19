package com.iaido.app

/**
 * Turns [throwable] into the bounded, redacted shape diagnostics may carry: the exception class
 * and its stack frames, with directories stripped.
 *
 * The exception *message* is dropped entirely. Messages routinely embed user-visible content
 * (typed text, candidate words, paths, clipboard contents), which the diagnostics contract
 * excludes, and no automatic scrubber can decide reliably which messages are safe. Frames are
 * code identifiers plus a line number, so they stay useful for diagnosis: a frame is only kept
 * when it matches [ERROR_FRAME_PATTERN], which allows `Class.method(File.kt:123)` and the
 * `<redacted>` placeholder and nothing else.
 */
fun redactThrowable(throwable: Throwable): RedactedThrowable {
    val frames = throwable.stackTrace
        .take(MAX_ERROR_FRAMES)
        .mapNotNull(::frameFor)
        .ifEmpty { listOf("<redacted>") }
    return RedactedThrowable(
        type = safeTypeName(throwable),
        frames = frames,
    )
}

private fun frameFor(frame: StackTraceElement): String? {
    val className = sanitize(frame.className)
    val methodName = sanitize(frame.methodName)
    val fileName = sanitize(frame.fileName ?: "Unknown")
    val line = frame.lineNumber.takeIf { it > 0 } ?: 0
    val rendered = "$className.$methodName($fileName:$line)"
    return rendered.takeIf { it.length <= MAX_ERROR_FRAME_LENGTH } ?: "<redacted>"
}

/**
 * Replaces anything outside the wire charset with `_`. A frame is sanitized rather than dropped:
 * Kotlin method names can contain characters (backticked names, spaces) the wire format does not
 * allow, and silently losing those frames would hide exactly the code path a reader needs.
 */
private fun sanitize(value: String): String =
    value.map { if (it.isLetterOrDigit() || it in "_.$") it else '_' }.joinToString("")

/** A simple class name that always satisfies [RedactedThrowable]'s type bounds. */
private fun safeTypeName(throwable: Throwable): String =
    throwable.javaClass.simpleName
        .filter { it.isLetterOrDigit() || it in "_.$<>" }
        .take(MAX_ERROR_TYPE_LENGTH)
        .ifEmpty { "Throwable" }

/** Re-serializes through the diagnostics allowlist before a value reaches local storage. */
fun validateDiagnostics(event: DiagnosticsEvent): DiagnosticsEvent =
    DiagnosticsEventCodec.decode(DiagnosticsEventCodec.encode(event))