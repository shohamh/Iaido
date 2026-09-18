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
MachineIdentifier = Annotated[
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


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DiagnosticsPayload(StrictModel):
    event_type: Literal["gesture_outcome"]
    outcome: Literal["ACCEPTED", "REJECTED", "CANCELLED"]


class NormalizedPointer(StrictModel):
    x: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    y: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)


class ResearchTextPayload(StrictModel):
    text: str = Field(min_length=1, max_length=256)


class ResearchTracePayload(StrictModel):
    points: list[NormalizedPointer] = Field(min_length=1, max_length=512)


ResearchPayload = ResearchTextPayload | ResearchTracePayload


class EnvelopeFields(StrictModel):
    schema_version: SchemaVersion
    event_id: MachineIdentifier
    batch_id: MachineIdentifier
    installation_id: MachineIdentifier
    session_id: MachineIdentifier
    occurred_at_ms: int = Field(ge=0)
    app_version: AppVersion
    build_type: Literal["debug", "release", "profile"]
    android_api: int = Field(ge=31, le=10_000)


class DiagnosticsEnvelope(EnvelopeFields):
    event_type: Literal["gesture_outcome"]
    payload: DiagnosticsPayload


class ResearchEnvelope(EnvelopeFields):
    event_type: Literal["text_sample", "gesture_trace"]
    payload: ResearchPayload

    @model_validator(mode="after")
    def payload_matches_event_type(self) -> "ResearchEnvelope":
        expected = (
            ResearchTextPayload
            if self.event_type == "text_sample"
            else ResearchTracePayload
        )
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
