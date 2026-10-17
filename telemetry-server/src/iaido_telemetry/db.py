from __future__ import annotations

import json
import re
import time
from collections.abc import Iterable, Mapping
from typing import Protocol
from sqlalchemy import (
    BigInteger,
    ForeignKey,
    String,
    UniqueConstraint,
    create_engine,
    func,
    inspect,
    select,
    text,
)
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker


UNKNOWN_VERSION = "unknown"
_VERSION_PATTERN = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-nightly\.(\d+))?$")


class Base(DeclarativeBase):
    pass


def version_sort_key(version: str) -> tuple:
    """Order app versions: ``unknown`` lowest; a release above its own nightlies; nightlies by run."""
    match = _VERSION_PATTERN.match(version)
    if match is None:
        return (0, 0, 0, 0, 0, 0)
    major, minor, patch, nightly = match.groups()
    if nightly is None:
        return (1, int(major), int(minor), int(patch), 1, 0)
    return (1, int(major), int(minor), int(patch), 0, int(nightly))


def derive_versions(events: Iterable[Mapping]) -> tuple[str, str]:
    """(app_version, algorithm_version) of a batch: taken from its newest event, else ``unknown``."""
    newest = None
    for event in events:
        if newest is None or event.get("occurred_at_ms", 0) >= newest.get("occurred_at_ms", 0):
            newest = event
    if newest is None:
        return UNKNOWN_VERSION, UNKNOWN_VERSION
    payload = newest.get("payload")
    algorithm = payload.get("algorithm_version") if isinstance(payload, Mapping) else None
    return (
        str(newest.get("app_version") or UNKNOWN_VERSION),
        UNKNOWN_VERSION if algorithm is None else str(algorithm),
    )


class InstallationRecord(Base):
    __tablename__ = "installations"

    installation_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    write_credential_hash: Mapped[str] = mapped_column(
        String(64), unique=True, index=True
    )
    deletion_credential_hash: Mapped[str] = mapped_column(
        String(64), unique=True, index=True
    )
    created_at_ms: Mapped[int] = mapped_column(BigInteger)


class BatchColumns:
    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    installation_id: Mapped[str] = mapped_column(
        ForeignKey("installations.installation_id", ondelete="CASCADE"), index=True
    )
    batch_id: Mapped[str] = mapped_column(String(128))
    checksum: Mapped[str] = mapped_column(String(64))
    object_key: Mapped[str] = mapped_column(String(512), unique=True)
    received_at_ms: Mapped[int] = mapped_column(BigInteger)
    app_version: Mapped[str] = mapped_column(
        String(32), index=True, default=UNKNOWN_VERSION, server_default=UNKNOWN_VERSION
    )
    algorithm_version: Mapped[str] = mapped_column(
        String(32), index=True, default=UNKNOWN_VERSION, server_default=UNKNOWN_VERSION
    )


class DiagnosticsBatchRecord(BatchColumns, Base):
    __tablename__ = "diagnostics_batches"
    __table_args__ = (
        UniqueConstraint("installation_id", "batch_id", name="uq_diagnostics_batch"),
    )


class ResearchBatchRecord(BatchColumns, Base):
    __tablename__ = "research_batches"
    __table_args__ = (
        UniqueConstraint("installation_id", "batch_id", name="uq_research_batch"),
    )


PLANE_RECORDS = {
    "diagnostics": DiagnosticsBatchRecord,
    "research": ResearchBatchRecord,
}


class AuditRecord(Base):
    """Append-only log of privacy-sensitive server operations.

    Every deletion, retention purge, and operator export writes one row here.
    Rows are never edited or removed by application code.
    """

    __tablename__ = "audit_records"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    action: Mapped[str] = mapped_column(String(32), index=True)
    plane: Mapped[str] = mapped_column(String(16), index=True)
    installation_id: Mapped[str] = mapped_column(String(128), index=True)
    actor: Mapped[str] = mapped_column(String(16))
    detail: Mapped[str] = mapped_column(String(256), default="")
    created_at_ms: Mapped[int] = mapped_column(BigInteger)


class EventFactColumns:
    """Small, non-identifying per-event facts used only for aggregate analysis.

    Deliberately excludes raw payload text/points so aggregate queries can
    never leak research content -- only event_type and a bounded enum-like
    discriminator (e.g. gesture outcome, error code, latency bucket) are kept.
    """

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    installation_id: Mapped[str] = mapped_column(String(128), index=True)
    batch_id: Mapped[str] = mapped_column(String(128), index=True)
    event_type: Mapped[str] = mapped_column(String(64), index=True)
    discriminator: Mapped[str] = mapped_column(String(64), default="")
    occurred_at_ms: Mapped[int] = mapped_column(BigInteger)


class DiagnosticsEventFact(EventFactColumns, Base):
    __tablename__ = "diagnostics_event_facts"


class ResearchEventFact(EventFactColumns, Base):
    __tablename__ = "research_event_facts"


PLANE_EVENT_FACTS = {
    "diagnostics": DiagnosticsEventFact,
    "research": ResearchEventFact,
}


class BatchIdentityConflict(Exception):
    pass


class TelemetryRepository(Protocol):
    def create_schema(self) -> None: ...

    def create_installation(
        self, installation_id: str, write_hash: str, deletion_hash: str
    ) -> InstallationRecord: ...

    def installation_for_write_hash(
        self, write_hash: str
    ) -> InstallationRecord | None: ...

    def installation_for_deletion_hash(
        self, deletion_hash: str
    ) -> InstallationRecord | None: ...

    def batch(self, plane: str, installation_id: str, batch_id: str): ...

    def add_batch(
        self,
        plane: str,
        installation_id: str,
        batch_id: str,
        checksum: str,
        object_key: str,
        app_version: str = UNKNOWN_VERSION,
        algorithm_version: str = UNKNOWN_VERSION,
    ) -> None: ...

    def delete_batches(self, plane: str, installation_id: str) -> None: ...

    def delete_batch(self, plane: str, installation_id: str, batch_id: str) -> None: ...

    def list_batches(self, plane: str, app_version: str | None = None): ...

    def version_summaries(self, plane: str) -> list[dict]: ...

    def backfill_versions(self, storage) -> None: ...

    def add_event_facts(
        self,
        plane: str,
        installation_id: str,
        batch_id: str,
        facts,
    ) -> None: ...

    def delete_event_facts(self, plane: str, installation_id: str) -> None: ...

    def delete_event_facts_for_batch(
        self, plane: str, installation_id: str, batch_id: str
    ) -> None: ...

    def event_counts(self, plane: str, event_type: str) -> int: ...

    def total_event_count(self, plane: str) -> int: ...

    def event_discriminator_counts(
        self, plane: str, event_type: str
    ) -> dict[str, int]: ...

    def add_audit(
        self,
        *,
        action: str,
        plane: str,
        installation_id: str,
        actor: str,
        detail: str = "",
    ) -> None: ...

    def audit_rows(
        self,
        *,
        action: str | None = None,
        plane: str | None = None,
        installation_id: str | None = None,
    ): ...


class SqlAlchemyRepository:
    def __init__(self, url: str):
        connect_args = {"check_same_thread": False} if url.startswith("sqlite") else {}
        self.engine = create_engine(url, connect_args=connect_args)
        self._sessions = sessionmaker(self.engine, expire_on_commit=False)

    def create_schema(self) -> None:
        Base.metadata.create_all(self.engine)
        self._add_missing_version_columns()

    def _add_missing_version_columns(self) -> None:
        """Additive migration for databases created before batches were tagged by version."""
        for model in PLANE_RECORDS.values():
            table = model.__tablename__
            existing = {column["name"] for column in inspect(self.engine).get_columns(table)}
            for column in ("app_version", "algorithm_version"):
                if column in existing:
                    continue
                with self.engine.begin() as connection:
                    connection.execute(
                        text(
                            f"ALTER TABLE {table} ADD COLUMN {column} "
                            f"VARCHAR(32) NOT NULL DEFAULT '{UNKNOWN_VERSION}'"
                        )
                    )
                    connection.execute(
                        text(f"CREATE INDEX IF NOT EXISTS ix_{table}_{column} ON {table} ({column})")
                    )

    def backfill_versions(self, storage) -> None:
        """Tag still-``unknown`` batches from their stored payload; unreadable ones stay unknown."""
        for model in PLANE_RECORDS.values():
            with self._sessions.begin() as session:
                for record in session.scalars(
                    select(model).where(
                        model.app_version == UNKNOWN_VERSION,
                        model.algorithm_version == UNKNOWN_VERSION,
                    )
                ):
                    try:
                        events = json.loads(storage.get(record.object_key)).get("events", [])
                    except Exception:
                        continue
                    record.app_version, record.algorithm_version = derive_versions(events)

    def create_installation(
        self, installation_id: str, write_hash: str, deletion_hash: str
    ) -> InstallationRecord:
        with self._sessions.begin() as session:
            record = InstallationRecord(
                installation_id=installation_id,
                write_credential_hash=write_hash,
                deletion_credential_hash=deletion_hash,
                created_at_ms=int(time.time() * 1000),
            )
            session.add(record)
        return record

    def installation_for_write_hash(self, write_hash: str) -> InstallationRecord | None:
        with self._sessions() as session:
            return session.scalar(
                select(InstallationRecord).where(
                    InstallationRecord.write_credential_hash == write_hash
                )
            )

    def installation_for_deletion_hash(
        self, deletion_hash: str
    ) -> InstallationRecord | None:
        with self._sessions() as session:
            return session.scalar(
                select(InstallationRecord).where(
                    InstallationRecord.deletion_credential_hash == deletion_hash
                )
            )

    def batch(self, plane: str, installation_id: str, batch_id: str):
        model = PLANE_RECORDS[plane]
        with self._sessions() as session:
            return session.scalar(
                select(model).where(
                    model.installation_id == installation_id,
                    model.batch_id == batch_id,
                )
            )

    def add_batch(
        self,
        plane: str,
        installation_id: str,
        batch_id: str,
        checksum: str,
        object_key: str,
        app_version: str = UNKNOWN_VERSION,
        algorithm_version: str = UNKNOWN_VERSION,
    ) -> None:
        model = PLANE_RECORDS[plane]
        try:
            with self._sessions.begin() as session:
                session.add(
                    model(
                        installation_id=installation_id,
                        batch_id=batch_id,
                        checksum=checksum,
                        object_key=object_key,
                        received_at_ms=int(time.time() * 1000),
                        app_version=app_version,
                        algorithm_version=algorithm_version,
                    )
                )
        except IntegrityError as error:
            existing = self.batch(plane, installation_id, batch_id)
            if (
                existing is not None
                and existing.checksum == checksum
                and existing.object_key == object_key
            ):
                return
            raise BatchIdentityConflict(batch_id) from error

    def delete_batches(self, plane: str, installation_id: str) -> None:
        model = PLANE_RECORDS[plane]
        with self._sessions.begin() as session:
            for record in session.scalars(
                select(model).where(model.installation_id == installation_id)
            ):
                session.delete(record)

    def delete_batch(self, plane: str, installation_id: str, batch_id: str) -> None:
        model = PLANE_RECORDS[plane]
        with self._sessions.begin() as session:
            record = session.scalar(
                select(model).where(
                    model.installation_id == installation_id,
                    model.batch_id == batch_id,
                )
            )
            if record is not None:
                session.delete(record)

    def list_batches(self, plane: str, app_version: str | None = None):
        model = PLANE_RECORDS[plane]
        query = select(model).order_by(model.id)
        if app_version is not None:
            query = query.where(model.app_version == app_version)
        with self._sessions() as session:
            return list(session.scalars(query))

    def version_summaries(self, plane: str) -> list[dict]:
        """One row per app version, newest first: batch count and event count (from event facts)."""
        model = PLANE_RECORDS[plane]
        facts = PLANE_EVENT_FACTS[plane]
        with self._sessions() as session:
            batch_counts = dict(
                session.execute(
                    select(model.app_version, func.count()).group_by(model.app_version)
                ).all()
            )
            event_counts = dict(
                session.execute(
                    select(model.app_version, func.count(facts.id))
                    .select_from(model)
                    .join(
                        facts,
                        (facts.installation_id == model.installation_id)
                        & (facts.batch_id == model.batch_id),
                    )
                    .group_by(model.app_version)
                ).all()
            )
        return [
            {"app_version": version, "batches": count, "events": event_counts.get(version, 0)}
            for version, count in sorted(
                batch_counts.items(), key=lambda item: version_sort_key(item[0]), reverse=True
            )
        ]

    def add_event_facts(
        self,
        plane: str,
        installation_id: str,
        batch_id: str,
        facts,
    ) -> None:
        model = PLANE_EVENT_FACTS[plane]
        with self._sessions.begin() as session:
            for event_type, discriminator, occurred_at_ms in facts:
                session.add(
                    model(
                        installation_id=installation_id,
                        batch_id=batch_id,
                        event_type=event_type,
                        discriminator=discriminator or "",
                        occurred_at_ms=occurred_at_ms,
                    )
                )

    def delete_event_facts(self, plane: str, installation_id: str) -> None:
        model = PLANE_EVENT_FACTS[plane]
        with self._sessions.begin() as session:
            for record in session.scalars(
                select(model).where(model.installation_id == installation_id)
            ):
                session.delete(record)

    def delete_event_facts_for_batch(
        self, plane: str, installation_id: str, batch_id: str
    ) -> None:
        model = PLANE_EVENT_FACTS[plane]
        with self._sessions.begin() as session:
            for record in session.scalars(
                select(model).where(
                    model.installation_id == installation_id,
                    model.batch_id == batch_id,
                )
            ):
                session.delete(record)

    def event_counts(self, plane: str, event_type: str) -> int:
        model = PLANE_EVENT_FACTS[plane]
        with self._sessions() as session:
            count = session.scalar(
                select(func.count())
                .select_from(model)
                .where(model.event_type == event_type)
            )
        return count or 0

    def total_event_count(self, plane: str) -> int:
        model = PLANE_EVENT_FACTS[plane]
        with self._sessions() as session:
            count = session.scalar(select(func.count()).select_from(model))
        return count or 0

    def event_discriminator_counts(
        self, plane: str, event_type: str
    ) -> dict[str, int]:
        model = PLANE_EVENT_FACTS[plane]
        with self._sessions() as session:
            rows = session.execute(
                select(model.discriminator, func.count())
                .where(model.event_type == event_type)
                .group_by(model.discriminator)
            ).all()
        return {discriminator: count for discriminator, count in rows}

    def add_audit(
        self,
        *,
        action: str,
        plane: str,
        installation_id: str,
        actor: str,
        detail: str = "",
    ) -> None:
        with self._sessions.begin() as session:
            session.add(
                AuditRecord(
                    action=action,
                    plane=plane,
                    installation_id=installation_id,
                    actor=actor,
                    detail=detail,
                    created_at_ms=int(time.time() * 1000),
                )
            )

    def audit_rows(
        self,
        *,
        action: str | None = None,
        plane: str | None = None,
        installation_id: str | None = None,
    ):
        filters = []
        if action is not None:
            filters.append(AuditRecord.action == action)
        if plane is not None:
            filters.append(AuditRecord.plane == plane)
        if installation_id is not None:
            filters.append(AuditRecord.installation_id == installation_id)
        with self._sessions() as session:
            return list(
                session.scalars(
                    select(AuditRecord).where(*filters).order_by(AuditRecord.id)
                )
            )


class PostgresRepository(SqlAlchemyRepository):
    def __init__(self, url: str):
        if not url.startswith("postgresql+psycopg://"):
            raise ValueError("PostgresRepository requires postgresql+psycopg://")
        super().__init__(url)


class SQLiteTestRepository(SqlAlchemyRepository):
    """SQLite adapter for isolated tests; never selected from environment config."""

    def __init__(self, url: str):
        if not url.startswith("sqlite"):
            raise ValueError("SQLiteTestRepository requires a SQLite URL")
        super().__init__(url)
