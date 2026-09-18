from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class Settings:
    database_url: str
    storage_root: Path
    operator_token: str
    max_request_bytes: int = 256 * 1024
    rate_limit_requests: int = 120
    rate_limit_window_seconds: int = 60

    def __post_init__(self) -> None:
        if not self.database_url:
            raise ValueError("database_url is required")
        if not self.operator_token:
            raise ValueError("operator_token is required")
        if self.max_request_bytes <= 0:
            raise ValueError("max_request_bytes must be positive")
        if self.rate_limit_requests <= 0:
            raise ValueError("rate_limit_requests must be positive")
        if self.rate_limit_window_seconds <= 0:
            raise ValueError("rate_limit_window_seconds must be positive")

    @classmethod
    def from_environment(cls) -> "Settings":
        operator_token = os.environ.get("IAIDO_OPERATOR_TOKEN", "")
        if not operator_token:
            raise RuntimeError(
                "IAIDO_OPERATOR_TOKEN must be supplied by the server environment"
            )
        return cls(
            database_url=os.environ.get(
                "IAIDO_DATABASE_URL", "sqlite:///./data/telemetry.db"
            ),
            storage_root=Path(os.environ.get("IAIDO_STORAGE_ROOT", "./data/objects")),
            operator_token=operator_token,
            max_request_bytes=int(
                os.environ.get("IAIDO_MAX_REQUEST_BYTES", str(256 * 1024))
            ),
            rate_limit_requests=int(os.environ.get("IAIDO_RATE_LIMIT_REQUESTS", "120")),
            rate_limit_window_seconds=int(
                os.environ.get("IAIDO_RATE_LIMIT_WINDOW_SECONDS", "60")
            ),
        )
