from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

# Wire contract shared with Android TelemetryBatch/TelemetryEnvelope serialization:
# - the outer batch_id is the durable queue-file identifier;
# - an envelope batch_id is an independent UUID captured with the event;
# - event_type is carried by the envelope, so research payloads do not repeat it.

UUID_PATTERN = (
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
UuidIdentifier = Annotated[
    str,
    Field(min_length=36, max_length=36, pattern=UUID_PATTERN),
]
QueueBatchIdentifier = Annotated[
    str,
    Field(
        min_length=49,
        max_length=100,
        pattern=(
            r"^batch-[0-9]{1,20}-[0-9]{1,20}-"
            r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        ),
    ),
]
AppVersion = Annotated[
    str,
    Field(min_length=5, max_length=14, pattern=r"^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}$"),
]
SchemaVersion = Literal[1]

# Research bounds, mirrored by the Android recorders (ResearchTraceLimits,
# MAX_SPAN_CODE_POINTS, MAX_CANDIDATES). Out-of-range values are rejected rather
# than truncated; only candidate lists are capped, and only on the device.
MAX_TRACE_POINTS = 512
MAX_SPAN_CODE_POINTS = 64
MAX_CANDIDATES = 5
# Redacted exception metadata bounds: a class name, at most 16 frames of at most 160 characters,
# and at most 32 breadcrumbs of crash context per event.
MAX_ERROR_FRAMES = 16
MAX_ERROR_FRAME_LENGTH = 160
MAX_BREADCRUMBS = 32
# MotionEvent.getActionMasked() values are 0..12; the server accepts that family
# with headroom and rejects anything outside a single byte rather than tracking
# Android's action constants.
MAX_POINTER_ACTION = 255
# An affected span (source/final text, one candidate): 1..64 Unicode code points.
BoundedSpan = Annotated[str, Field(min_length=1, max_length=MAX_SPAN_CODE_POINTS)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class GestureOutcomePayload(StrictModel):
    event_type: Literal["gesture_outcome"]
    outcome: Literal["ACCEPTED", "REJECTED", "CANCELLED"]


# Redacted exception metadata. Deliberately has no message field: exception messages can embed
# typed text, and the diagnostics contract excludes readable words. Frames are code identifiers
# plus a line number, with directories (and therefore user/developer paths) stripped, so a frame
# matches `Class.method(File.kt:123)` or the `<redacted>` placeholder - never a drive letter.
ErrorFrame = Annotated[
    str,
    Field(
        max_length=MAX_ERROR_FRAME_LENGTH,
        pattern=r"^(?:<redacted>|[A-Za-z0-9_.$<>]+\([A-Za-z0-9_.$]*:[0-9]{1,7}\))$",
    ),
]
RedactedErrorCode = Literal[
    "RECOGNITION_FAILED",
    "CORRECTION_FAILED",
    "COMMAND_FAILED",
    "SETTINGS_FAILED",
    "UNKNOWN",
]
BreadcrumbKind = Literal[
    "APP_START",
    "IME_SESSION_START",
    "IME_SESSION_FINISH",
    "GESTURE_OUTCOME",
    "RECOGNITION_LATENCY",
    "SUGGESTION_ACTION",
    "RUNTIME_ERROR",
]


class RedactedError(StrictModel):
    """Collected exception shape: the class and up to [MAX_ERROR_FRAMES] frames."""

    type: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.$<>]+$")
    frames: list[ErrorFrame] = Field(default_factory=list, max_length=MAX_ERROR_FRAMES)


class RuntimeErrorPayload(StrictModel):
    event_type: Literal["runtime_error"]
    code: RedactedErrorCode
    error: RedactedError


class BreadcrumbRecord(StrictModel):
    """One bounded, enum-only breadcrumb kept as crash context. Never free text."""

    kind: BreadcrumbKind
    count: int = Field(ge=1, le=10_000)
    gesture_kind: Literal["SWIPE", "SPLIT", "PUNCTUATION", "COMMAND"] | None = None
    outcome: Literal["ACCEPTED", "REJECTED", "CANCELLED"] | None = None
    latency_bucket: Literal[
        "UNDER_50_MS",
        "FROM_50_TO_99_MS",
        "FROM_100_TO_249_MS",
        "FROM_250_TO_499_MS",
        "FROM_500_TO_999_MS",
        "OVER_1000_MS",
    ] | None = None
    suggestion_action: Literal["REPLACEMENT", "SUGGESTION_PICK", "UNDO"] | None = None
    error_code: RedactedErrorCode | None = None
    error: RedactedError | None = None


class CrashPayload(StrictModel):
    event_type: Literal["crash"]
    error: RedactedError
    breadcrumbs: list[BreadcrumbRecord] = Field(
        default_factory=list, max_length=MAX_BREADCRUMBS
    )


DiagnosticsPayload = GestureOutcomePayload | RuntimeErrorPayload | CrashPayload


class TracePoint(StrictModel):
    pointer_id: int = Field(ge=0)
    action: int = Field(ge=0, le=MAX_POINTER_ACTION)
    time_offset_ms: int = Field(ge=0)
    x: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    y: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)


class ResearchTextPayload(StrictModel):
    text: str = Field(min_length=1, max_length=256)


class GestureTracePayload(StrictModel):
    trace_id: UuidIdentifier
    classification: Literal[
        "swipe",
        "tap",
        "flick",
        "split",
        "command",
        "punctuation",
        "backspace",
        "failed",
        "cancelled",
    ]
    language: Literal["ENGLISH", "HEBREW"]
    layout_id: str = Field(min_length=1, max_length=32)
    algorithm_version: int = Field(ge=1)
    points: list[TracePoint] = Field(min_length=1, max_length=MAX_TRACE_POINTS)

    @model_validator(mode="after")
    def points_are_time_ordered(self) -> "GestureTracePayload":
        offsets = [point.time_offset_ms for point in self.points]
        if any(later < earlier for earlier, later in zip(offsets, offsets[1:])):
            raise ValueError("trace point time offsets must be monotonic")
        return self


class ResearchCorrectionPayload(StrictModel):
    correction_id: UuidIdentifier
    # Absent when no gesture trace was captured for the corrected span; the
    # Android recorder omits the key entirely instead of sending null.
    trace_id: UuidIdentifier | None = None
    action: Literal["candidate_selected", "manual_edit", "undo", "flow_correction"]
    source_text: BoundedSpan
    final_text: BoundedSpan
    candidates: list[BoundedSpan] = Field(max_length=MAX_CANDIDATES)
    algorithm_version: int = Field(ge=1)


ResearchPayload = ResearchTextPayload | GestureTracePayload | ResearchCorrectionPayload


class EnvelopeFields(StrictModel):
    schema_version: SchemaVersion
    event_id: UuidIdentifier
    batch_id: UuidIdentifier
    installation_id: UuidIdentifier
    session_id: UuidIdentifier
    occurred_at_ms: int = Field(ge=0)
    app_version: AppVersion
    build_type: Literal["debug", "release", "profile"]
    android_api: int = Field(ge=31, le=10_000)


class DiagnosticsEnvelope(EnvelopeFields):
    event_type: Literal["gesture_outcome", "runtime_error", "crash"]
    payload: DiagnosticsPayload

    @model_validator(mode="after")
    def payload_matches_event_type(self) -> "DiagnosticsEnvelope":
        expected = {
            "gesture_outcome": GestureOutcomePayload,
            "runtime_error": RuntimeErrorPayload,
            "crash": CrashPayload,
        }[self.event_type]
        if not isinstance(self.payload, expected):
            raise ValueError("diagnostics payload does not match event_type")
        return self


class ResearchEnvelope(EnvelopeFields):
    event_type: Literal["text_sample", "gesture_trace", "research_correction"]
    payload: ResearchPayload

    @model_validator(mode="after")
    def payload_matches_event_type(self) -> "ResearchEnvelope":
        expected = {
            "text_sample": ResearchTextPayload,
            "gesture_trace": GestureTracePayload,
            "research_correction": ResearchCorrectionPayload,
        }[self.event_type]
        if not isinstance(self.payload, expected):
            raise ValueError("research payload does not match event_type")
        return self


class DiagnosticsBatch(StrictModel):
    schema_version: SchemaVersion = 1
    batch_id: QueueBatchIdentifier
    events: list[DiagnosticsEnvelope] = Field(max_length=100)


class ResearchBatch(StrictModel):
    schema_version: SchemaVersion = 1
    batch_id: QueueBatchIdentifier
    events: list[ResearchEnvelope] = Field(max_length=100)


class InstallationResponse(StrictModel):
    installation_id: str
    write_credential: str
    deletion_credential: str


class BatchAcknowledgement(StrictModel):
    batch_id: str
    accepted: Literal[True] = True


class DeletionReceipt(StrictModel):
    receipt_id: str
    installation_id: str
    plane: Literal["diagnostics", "research"]
    deleted: Literal[True] = True


class OperatorBatch(StrictModel):
    installation_id: str
    batch_id: str
    checksum: str
    object_key: str
    received_at_ms: int


class OperatorBatchList(StrictModel):
    plane: Literal["diagnostics", "research"]
    batches: list[OperatorBatch]
