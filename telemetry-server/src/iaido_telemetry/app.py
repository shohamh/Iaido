from __future__ import annotations

import hashlib
import json
import threading
import time
import uuid
from collections import defaultdict, deque
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse
from starlette.types import Message, Receive, Scope, Send

from .auth import (
    bearer_credential,
    credential_hash,
    credentials_match,
    deletion_receipt_id,
    issue_credential,
)
from .config import Settings
from .db import BatchIdentityConflict, Database, InstallationRecord
from .schemas import (
    BatchAcknowledgement,
    DeletionReceipt,
    DiagnosticsBatch,
    InstallationResponse,
    OperatorBatch,
    OperatorBatchList,
    ResearchBatch,
)
from .storage import LocalObjectStorage, ObjectConflictError

Plane = Literal["diagnostics", "research"]


class RateLimiter:
    def __init__(self, requests: int, window_seconds: int):
        self.requests = requests
        self.window_seconds = window_seconds
        self._requests: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            recent = self._requests[key]
            while recent and recent[0] <= now - self.window_seconds:
                recent.popleft()
            if len(recent) >= self.requests:
                return False
            recent.append(now)
            return True


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


def create_app(settings: Settings | None = None) -> FastAPI:
    active_settings = settings or Settings.from_environment()
    database = Database(active_settings.database_url)
    database.create_schema()
    storage = LocalObjectStorage(active_settings.storage_root)
    limiter = RateLimiter(
        active_settings.rate_limit_requests,
        active_settings.rate_limit_window_seconds,
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
        installation_id = uuid.uuid4().hex
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
            if event.batch_id != batch.batch_id:
                raise HTTPException(
                    status_code=422, detail="Event batch_id does not match batch"
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
        storage.delete_installation(plane, installation.installation_id)
        database.delete_batches(plane, installation.installation_id)
        return DeletionReceipt(
            receipt_id=deletion_receipt_id(
                installation.deletion_credential_hash, plane
            ),
            installation_id=installation.installation_id,
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

    return app
