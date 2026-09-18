package com.iaido.app

data class RedactedThrowable(
    val type: String,
    val message: String?,
    val stackTrace: String,
)

/** Final defense for diagnostic failures: no exception-provided text is retained. */
fun redactThrowable(throwable: Throwable): RedactedThrowable = RedactedThrowable(
    type = "exception",
    message = null,
    stackTrace = "<redacted>",
)

/** Re-serializes through the diagnostics allowlist before a value reaches local storage. */
fun validateDiagnostics(event: DiagnosticsEvent): DiagnosticsEvent =
    DiagnosticsEventCodec.decode(DiagnosticsEventCodec.encode(event))
