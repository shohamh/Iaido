from __future__ import annotations

import hashlib
import io
import json
import threading
import time
import uuid
from collections import OrderedDict, deque
from collections.abc import Callable
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse, Response
from starlette.types import Message, Receive, Scope, Send

from .auth import (
    bearer_credential,
    credential_hash,
    credentials_match,
    deletion_receipt_id,
    issue_credential,
)
from .config import Settings
from .dashboard import register_dashboard
from .db import (
    BatchIdentityConflict,
    InstallationRecord,
    PostgresRepository,
    TelemetryRepository,
)
from .exports import ExportAuthError, export_plane
from .schemas import (
    BatchAcknowledgement,
    DeletionReceipt,
    DiagnosticsBatch,
    InstallationResponse,
    OperatorBatch,
    OperatorBatchList,
    ResearchBatch,
)
from .storage import ObjectConflictError, ObjectStorage, S3ObjectStorage

Plane = Literal["diagnostics", "research"]

#: Small, bounded set of payload fields that may serve as an aggregate
#: discriminator (e.g. the gesture outcome enum, a stable error code, a
#: latency/candidate bucket, or a suggestion/correction action enum). Never
#: includes free text or coordinate data, so aggregate event facts can never
#: carry research/diagnostics payload content.
DISCRIMINATOR_FIELDS = ("outcome", "action", "error_code", "bucket", "latency_bucket", "classification")


def _event_discriminator(payload: dict) -> str | None:
    for field in DISCRIMINATOR_FIELDS:
        if field in payload:
            return str(payload[field])
    error = payload.get("error")
    if isinstance(error, dict) and "type" in error:
        return str(error["type"])
    return None


def delete_installation_plane(
    database: TelemetryRepository,
    storage: ObjectStorage,
    installation_id: str,
    plane: Plane,
    *,
    actor: str,
) -> None:
    """Remove one installation's data for exactly one plane.

    Removes both the object-storage payloads and the database batch/event-fact
    rows for ``plane`` in a single operation, then writes a deletion audit
    record. Never touches the other plane's prefix or tables. Safe to call
    repeatedly for the same installation/plane -- a second call finds nothing
    left to remove and still succeeds (idempotent), though it still appends
    its own audit record so the audit trail reflects every deletion attempt.
    """
    storage.delete_installation(plane, installation_id)
    database.delete_batches(plane, installation_id)
    database.delete_event_facts(plane, installation_id)
    database.add_audit(
        action="delete",
        plane=plane,
        installation_id=installation_id,
        actor=actor,
    )


class RateLimiter:
    def __init__(
        self,
        requests: int,
        window_seconds: int,
        max_buckets: int,
        clock: Callable[[], float] = time.monotonic,
    ):
        self.requests = requests
        self.window_seconds = window_seconds
        self.max_buckets = max_buckets
        self._clock = clock
        self._requests: OrderedDict[str, deque[float]] = OrderedDict()
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = self._clock()
        with self._lock:
            self._evict_stale(now)
            if key not in self._requests and len(self._requests) >= self.max_buckets:
                self._requests.popitem(last=False)
            recent = self._requests.setdefault(key, deque())
            self._requests.move_to_end(key)
            while recent and recent[0] <= now - self.window_seconds:
                recent.popleft()
            if len(recent) >= self.requests:
                return False
            recent.append(now)
            return True

    @property
    def bucket_count(self) -> int:
        with self._lock:
            return len(self._requests)

    def _evict_stale(self, now: float) -> None:
        stale_before = now - self.window_seconds
        for key, recent in list(self._requests.items()):
            while recent and recent[0] <= stale_before:
                recent.popleft()
            if not recent:
                del self._requests[key]


class RequestTooLarge(Exception):
    pass


class IngestionBoundaryMiddleware:
    def __init__(self, app, settings: Settings, limiter: RateLimiter):
        self.app = app
        self.settings = settings
        self.limiter = limiter

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith("/v1/"):
            await self.app(scope, receive, send)
            return

        if scope.get("scheme") != "https":
            await self._error(scope, receive, send, 426, "HTTPS required")
            return

        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        content_length = headers.get(b"content-length")
        if content_length:
            try:
                declared_length = int(content_length)
            except ValueError:
                await self._error(scope, receive, send, 400, "Invalid Content-Length")
                return
            if declared_length > self.settings.max_request_bytes:
                await self._error(scope, receive, send, 413, "Request body too large")
                return

        if scope.get("method") in {"POST", "DELETE"}:
            client = scope.get("client")
            client_host = client[0] if client else "unknown"
            credential = headers.get(b"authorization", b"").decode(
                "utf-8", errors="replace"
            )
            keys = [f"client:{client_host}"]
            if credential:
                keys.append(
                    "credential:"
                    + hashlib.sha256(credential.encode("utf-8")).hexdigest()
                )
            if not all(self.limiter.allow(key) for key in keys):
                await self._error(scope, receive, send, 429, "Rate limit exceeded")
                return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.settings.max_request_bytes:
                    raise RequestTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except RequestTooLarge:
            await self._error(scope, receive, send, 413, "Request body too large")

    @staticmethod
    async def _error(
        scope: Scope,
        receive: Receive,
        send: Send,
        status_code: int,
        detail: str,
    ) -> None:
        await JSONResponse(status_code=status_code, content={"detail": detail})(
            scope, receive, send
        )


def create_app(
    settings: Settings | None = None,
    *,
    database: TelemetryRepository | None = None,
    storage: ObjectStorage | None = None,
) -> FastAPI:
    active_settings = settings or Settings.from_environment()
    if (database is None) != (storage is None):
        raise ValueError("database and storage adapters must be supplied together")
    if database is None:
        active_settings.validate_production()
        database = PostgresRepository(active_settings.database_url)
        storage = S3ObjectStorage(
            bucket=active_settings.s3_bucket,
            endpoint_url=active_settings.s3_endpoint_url,
            access_key=active_settings.s3_access_key,
            secret_key=active_settings.s3_secret_key,
            region=active_settings.s3_region,
        )
    assert storage is not None
    database.create_schema()
    limiter = RateLimiter(
        active_settings.rate_limit_requests,
        active_settings.rate_limit_window_seconds,
        active_settings.rate_limit_max_buckets,
    )
    app = FastAPI(title="Iaido Telemetry", version="1")
    app.state.settings = active_settings
    app.state.database = database
    app.state.storage = storage
    app.add_middleware(
        IngestionBoundaryMiddleware,
        settings=active_settings,
        limiter=limiter,
    )

    def write_installation(request: Request) -> InstallationRecord:
        credential = bearer_credential(request)
        installation = database.installation_for_write_hash(credential_hash(credential))
        if installation is None:
            raise HTTPException(
                status_code=401, detail="Invalid installation credential"
            )
        return installation

    def deletion_installation(request: Request) -> InstallationRecord:
        credential = bearer_credential(request)
        installation = database.installation_for_deletion_hash(
            credential_hash(credential)
        )
        if installation is None:
            raise HTTPException(status_code=401, detail="Invalid deletion credential")
        return installation

    def require_operator(request: Request) -> None:
        credential = bearer_credential(request)
        if not credentials_match(credential, active_settings.operator_token):
            raise HTTPException(status_code=401, detail="Invalid operator credential")

    @app.post(
        "/v1/installations",
        response_model=InstallationResponse,
        status_code=status.HTTP_201_CREATED,
    )
    def create_installation() -> InstallationResponse:
        installation_id = str(uuid.uuid4())
        write_credential = issue_credential()
        deletion_credential = issue_credential()
        database.create_installation(
            installation_id,
            credential_hash(write_credential),
            credential_hash(deletion_credential),
        )
        return InstallationResponse(
            installation_id=installation_id,
            write_credential=write_credential,
            deletion_credential=deletion_credential,
        )

    def ingest(
        plane: Plane,
        batch: DiagnosticsBatch | ResearchBatch,
        installation: InstallationRecord,
    ) -> BatchAcknowledgement:
        for event in batch.events:
            if event.installation_id != installation.installation_id:
                raise HTTPException(
                    status_code=422,
                    detail="Event installation_id does not match credential",
                )
        raw = json.dumps(
            batch.model_dump(mode="json"),
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        checksum = hashlib.sha256(raw).hexdigest()
        existing = database.batch(plane, installation.installation_id, batch.batch_id)
        if existing is not None:
            if not credentials_match(checksum, existing.checksum):
                raise HTTPException(
                    status_code=409, detail="Batch id already has different content"
                )
            return BatchAcknowledgement(batch_id=batch.batch_id)

        object_key = storage.object_key(
            plane, installation.installation_id, batch.batch_id
        )
        try:
            storage.put_if_absent(object_key, raw)
        except ObjectConflictError as error:
            raise HTTPException(
                status_code=409, detail="Batch object already has different content"
            ) from error
        try:
            database.add_batch(
                plane,
                installation.installation_id,
                batch.batch_id,
                checksum,
                object_key,
            )
        except BatchIdentityConflict as error:
            raise HTTPException(
                status_code=409, detail="Batch id already has different content"
            ) from error
        except Exception:
            current = database.batch(
                plane, installation.installation_id, batch.batch_id
            )
            if current is None:
                storage.delete(object_key)
            elif credentials_match(checksum, current.checksum):
                return BatchAcknowledgement(batch_id=batch.batch_id)
            raise
        else:
            facts = [
                (
                    event.event_type,
                    _event_discriminator(event.payload.model_dump(mode="json")),
                    event.occurred_at_ms,
                )
                for event in batch.events
            ]
            database.add_event_facts(
                plane, installation.installation_id, batch.batch_id, facts
            )
        return BatchAcknowledgement(batch_id=batch.batch_id)

    @app.post(
        "/v1/diagnostics/batches",
        response_model=BatchAcknowledgement,
        status_code=status.HTTP_202_ACCEPTED,
    )
    def ingest_diagnostics(
        batch: DiagnosticsBatch,
        installation: InstallationRecord = Depends(write_installation),
    ) -> BatchAcknowledgement:
        return ingest("diagnostics", batch, installation)

    @app.post(
        "/v1/research/batches",
        response_model=BatchAcknowledgement,
        status_code=status.HTTP_202_ACCEPTED,
    )
    def ingest_research(
        batch: ResearchBatch,
        installation: InstallationRecord = Depends(write_installation),
    ) -> BatchAcknowledgement:
        return ingest("research", batch, installation)

    @app.delete("/v1/{plane}", response_model=DeletionReceipt)
    def delete_plane(
        plane: Plane,
        installation: InstallationRecord = Depends(deletion_installation),
    ) -> DeletionReceipt:
        delete_installation_plane(
            database, storage, installation.installation_id, plane, actor="device"
        )
        return DeletionReceipt(
            receipt_id=deletion_receipt_id(
                installation.deletion_credential_hash, plane
            ),
            installation_id=installation.installation_id,
            plane=plane,
        )

    @app.delete(
        "/v1/operator/installations/{installation_id}/{plane}",
        response_model=DeletionReceipt,
        dependencies=[Depends(require_operator)],
    )
    def operator_delete_plane(installation_id: str, plane: Plane) -> DeletionReceipt:
        delete_installation_plane(
            database, storage, installation_id, plane, actor="operator"
        )
        receipt_id = hashlib.sha256(
            f"operator-deletion-v1:{plane}:{installation_id}".encode("utf-8")
        ).hexdigest()
        return DeletionReceipt(
            receipt_id=receipt_id,
            installation_id=installation_id,
            plane=plane,
        )

    @app.get(
        "/v1/operator/{plane}/batches",
        response_model=OperatorBatchList,
        dependencies=[Depends(require_operator)],
    )
    def operator_batches(plane: Plane) -> OperatorBatchList:
        records = database.list_batches(plane)
        return OperatorBatchList(
            plane=plane,
            batches=[
                OperatorBatch(
                    installation_id=record.installation_id,
                    batch_id=record.batch_id,
                    checksum=record.checksum,
                    object_key=record.object_key,
                    received_at_ms=record.received_at_ms,
                )
                for record in records
            ],
        )

    @app.get("/v1/operator/{plane}/export")
    def operator_export(plane: Plane, start_ms: int, end_ms: int, request: Request):
        token = bearer_credential(request)
        buffer = io.StringIO()
        try:
            export_plane(
                database,
                storage,
                active_settings,
                plane,
                start_ms,
                end_ms,
                buffer,
                operator_token=token,
            )
        except ExportAuthError as error:
            raise HTTPException(
                status_code=401, detail="Invalid operator credential"
            ) from error
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        return Response(content=buffer.getvalue(), media_type="application/x-ndjson")

    @app.get(
        "/v1/operator/aggregates/crash-counts",
        dependencies=[Depends(require_operator)],
    )
    def crash_counts() -> dict:
        return {
            "plane": "diagnostics",
            "count": database.event_counts("diagnostics", "crash"),
        }

    @app.get(
        "/v1/operator/aggregates/runtime-error-rate",
        dependencies=[Depends(require_operator)],
    )
    def runtime_error_rate() -> dict:
        total = database.total_event_count("diagnostics")
        errors = database.event_counts("diagnostics", "runtime_error")
        return {
            "plane": "diagnostics",
            "errors": errors,
            "total": total,
            "rate": (errors / total) if total else 0.0,
        }

    @app.get(
        "/v1/operator/aggregates/gesture-outcomes",
        dependencies=[Depends(require_operator)],
    )
    def gesture_outcome_counts() -> dict:
        return {
            "plane": "diagnostics",
            "counts": database.event_discriminator_counts(
                "diagnostics", "gesture_outcome"
            ),
        }

    @app.get(
        "/v1/operator/aggregates/correction-actions",
        dependencies=[Depends(require_operator)],
    )
    def correction_action_counts() -> dict:
        return {
            "plane": "diagnostics",
            "counts": database.event_discriminator_counts(
                "diagnostics", "suggestion_action"
            ),
        }

    @app.get(
        "/v1/operator/aggregates/latency-buckets",
        dependencies=[Depends(require_operator)],
    )
    def latency_buckets() -> dict:
        return {
            "plane": "diagnostics",
            "counts": database.event_discriminator_counts("diagnostics", "latency"),
        }

    register_dashboard(app, active_settings, database, storage)

    return app
