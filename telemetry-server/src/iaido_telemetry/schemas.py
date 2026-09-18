from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


Identifier = Annotated[
    str,
    Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9._-]+$"),
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
    event_type: Literal["text_sample"]
    text: str = Field(min_length=1, max_length=256)


class ResearchTracePayload(StrictModel):
    event_type: Literal["gesture_trace"]
    points: list[NormalizedPointer] = Field(min_length=1, max_length=512)


ResearchPayload = Annotated[
    ResearchTextPayload | ResearchTracePayload,
    Field(discriminator="event_type"),
]


class EnvelopeFields(StrictModel):
    schema_version: SchemaVersion
    event_id: Identifier
    batch_id: Identifier
    installation_id: Identifier
    session_id: Identifier
    occurred_at_ms: int = Field(ge=0)
    app_version: str = Field(min_length=1, max_length=64)
    build_type: str = Field(min_length=1, max_length=32)
    android_api: int = Field(ge=31, le=10_000)


class DiagnosticsEnvelope(EnvelopeFields):
    event_type: Literal["gesture_outcome"]
    payload: DiagnosticsPayload


class ResearchEnvelope(EnvelopeFields):
    event_type: Literal["text_sample", "gesture_trace"]
    payload: ResearchPayload

    @model_validator(mode="after")
    def event_types_match(self) -> "ResearchEnvelope":
        if self.event_type != self.payload.event_type:
            raise ValueError("envelope and payload event types must match")
        return self


class DiagnosticsBatch(StrictModel):
    schema_version: SchemaVersion = 1
    batch_id: Identifier
    events: list[DiagnosticsEnvelope] = Field(max_length=100)


class ResearchBatch(StrictModel):
    schema_version: SchemaVersion = 1
    batch_id: Identifier
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
