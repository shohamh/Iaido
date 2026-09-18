from __future__ import annotations

import time
from typing import Protocol
from sqlalchemy import (
    BigInteger,
    ForeignKey,
    String,
    UniqueConstraint,
    create_engine,
    select,
)
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker


class Base(DeclarativeBase):
    pass


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
    ) -> None: ...

    def delete_batches(self, plane: str, installation_id: str) -> None: ...

    def list_batches(self, plane: str): ...


class SqlAlchemyRepository:
    def __init__(self, url: str):
        connect_args = {"check_same_thread": False} if url.startswith("sqlite") else {}
        self.engine = create_engine(url, connect_args=connect_args)
        self._sessions = sessionmaker(self.engine, expire_on_commit=False)

    def create_schema(self) -> None:
        Base.metadata.create_all(self.engine)

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

    def list_batches(self, plane: str):
        model = PLANE_RECORDS[plane]
        with self._sessions() as session:
            return list(session.scalars(select(model).order_by(model.id)))


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
