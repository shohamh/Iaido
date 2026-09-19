from __future__ import annotations

import os
from dataclasses import dataclass

# A deployment-set dashboard password must not be trivially guessable on a public dashboard;
# leaving it unset reuses the operator token instead, which is always generated.
MIN_DASHBOARD_PASSWORD_LENGTH = 12


@dataclass(frozen=True, slots=True)
class Settings:
    database_url: str
    operator_token: str
    # Dashboard login. The password defaults to the operator token, so a deployment that sets
    # nothing gains no new (and no weaker) secret; set IAIDO_DASHBOARD_PASSWORD to give the
    # browser login its own credential.
    dashboard_username: str = "iaido"
    dashboard_password: str = ""
    s3_endpoint_url: str = ""
    s3_bucket: str = ""
    s3_access_key: str = ""
    s3_secret_key: str = ""
    s3_region: str = "us-east-1"
    max_request_bytes: int = 256 * 1024
    rate_limit_requests: int = 120
    rate_limit_window_seconds: int = 60
    rate_limit_max_buckets: int = 10_000
    diagnostics_retention_days: int = 90
    research_retention_days: int = 365

    DEFAULT_DIAGNOSTICS_RETENTION_DAYS = 90
    DEFAULT_RESEARCH_RETENTION_DAYS = 365

    def __post_init__(self) -> None:
        if not self.database_url:
            raise ValueError("database_url is required")
        if not self.operator_token:
            raise ValueError("operator_token is required")
        if not self.dashboard_username:
            raise ValueError("dashboard_username must not be empty")
        if self.dashboard_password and len(self.dashboard_password) < MIN_DASHBOARD_PASSWORD_LENGTH:
            raise ValueError(
                "dashboard_password must be at least "
                f"{MIN_DASHBOARD_PASSWORD_LENGTH} characters when set (leave it empty to reuse "
                "the operator token)"
            )
        if self.max_request_bytes <= 0:
            raise ValueError("max_request_bytes must be positive")
        if self.rate_limit_requests <= 0:
            raise ValueError("rate_limit_requests must be positive")
        if self.rate_limit_window_seconds <= 0:
            raise ValueError("rate_limit_window_seconds must be positive")
        if self.rate_limit_max_buckets <= 0:
            raise ValueError("rate_limit_max_buckets must be positive")
        if not (
            0 < self.diagnostics_retention_days <= self.DEFAULT_DIAGNOSTICS_RETENTION_DAYS
        ):
            raise ValueError(
                "diagnostics_retention_days must be between 1 and "
                f"{self.DEFAULT_DIAGNOSTICS_RETENTION_DAYS} (deployment config may only "
                "shorten the default retention, never lengthen it)"
            )
        if not (
            0 < self.research_retention_days <= self.DEFAULT_RESEARCH_RETENTION_DAYS
        ):
            raise ValueError(
                "research_retention_days must be between 1 and "
                f"{self.DEFAULT_RESEARCH_RETENTION_DAYS} (deployment config may only "
                "shorten the default retention, never lengthen it)"
            )

    @property
    def effective_dashboard_password(self) -> str:
        """Password the dashboard accepts: the operator token unless one is configured."""
        return self.dashboard_password or self.operator_token

    def validate_production(self) -> None:
        if not self.database_url.startswith("postgresql+psycopg://"):
            raise RuntimeError(
                "Production telemetry storage requires a PostgreSQL psycopg URL"
            )
        missing = [
            name
            for name, value in {
                "IAIDO_S3_ENDPOINT_URL": self.s3_endpoint_url,
                "IAIDO_S3_BUCKET": self.s3_bucket,
                "IAIDO_S3_ACCESS_KEY": self.s3_access_key,
                "IAIDO_S3_SECRET_KEY": self.s3_secret_key,
            }.items()
            if not value
        ]
        if missing:
            raise RuntimeError(
                "Production telemetry storage requires: " + ", ".join(missing)
            )

    @classmethod
    def from_environment(cls) -> "Settings":
        operator_token = os.environ.get("IAIDO_OPERATOR_TOKEN", "")
        if not operator_token:
            raise RuntimeError(
                "IAIDO_OPERATOR_TOKEN must be supplied by the server environment"
            )
        database_url = os.environ.get("IAIDO_DATABASE_URL", "")
        if not database_url:
            raise RuntimeError(
                "IAIDO_DATABASE_URL must be supplied by the server environment"
            )
        settings = cls(
            database_url=database_url,
            operator_token=operator_token,
            dashboard_username=os.environ.get("IAIDO_DASHBOARD_USERNAME", "iaido"),
            dashboard_password=os.environ.get("IAIDO_DASHBOARD_PASSWORD", ""),
            s3_endpoint_url=os.environ.get("IAIDO_S3_ENDPOINT_URL", ""),
            s3_bucket=os.environ.get("IAIDO_S3_BUCKET", ""),
            s3_access_key=os.environ.get("IAIDO_S3_ACCESS_KEY", ""),
            s3_secret_key=os.environ.get("IAIDO_S3_SECRET_KEY", ""),
            s3_region=os.environ.get("IAIDO_S3_REGION", "us-east-1"),
            max_request_bytes=int(
                os.environ.get("IAIDO_MAX_REQUEST_BYTES", str(256 * 1024))
            ),
            rate_limit_requests=int(os.environ.get("IAIDO_RATE_LIMIT_REQUESTS", "120")),
            rate_limit_window_seconds=int(
                os.environ.get("IAIDO_RATE_LIMIT_WINDOW_SECONDS", "60")
            ),
            rate_limit_max_buckets=int(
                os.environ.get("IAIDO_RATE_LIMIT_MAX_BUCKETS", "10000")
            ),
            diagnostics_retention_days=int(
                os.environ.get("IAIDO_DIAGNOSTICS_RETENTION_DAYS", "90")
            ),
            research_retention_days=int(
                os.environ.get("IAIDO_RESEARCH_RETENTION_DAYS", "365")
            ),
        )
        settings.validate_production()
        return settings
