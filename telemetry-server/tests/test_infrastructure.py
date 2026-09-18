from __future__ import annotations

import pytest

from iaido_telemetry.app import RateLimiter
from iaido_telemetry.config import Settings
from iaido_telemetry import storage as storage_module


def test_environment_configuration_rejects_local_database(monkeypatch):
    monkeypatch.setenv("IAIDO_OPERATOR_TOKEN", "operator-test-token")
    monkeypatch.setenv("IAIDO_DATABASE_URL", "sqlite:///local.db")
    monkeypatch.setenv("IAIDO_S3_ENDPOINT_URL", "http://minio:9000")
    monkeypatch.setenv("IAIDO_S3_BUCKET", "telemetry")
    monkeypatch.setenv("IAIDO_S3_ACCESS_KEY", "access-key")
    monkeypatch.setenv("IAIDO_S3_SECRET_KEY", "secret-key")

    with pytest.raises(RuntimeError, match="PostgreSQL"):
        Settings.from_environment()


def test_rate_limiter_evicts_stale_and_excess_identity_buckets():
    now = [0.0]
    limiter = RateLimiter(
        requests=10,
        window_seconds=10,
        max_buckets=2,
        clock=lambda: now[0],
    )

    assert limiter.allow("client:a") is True
    assert limiter.allow("client:b") is True
    assert limiter.allow("client:c") is True
    assert limiter.bucket_count == 2

    now[0] = 11.0
    assert limiter.allow("client:d") is True
    assert limiter.bucket_count == 1


class FakeS3Client:
    def __init__(self):
        self.objects: dict[tuple[str, str], bytes] = {}

    def put_object(self, *, Bucket, Key, Body, IfNoneMatch):
        assert IfNoneMatch == "*"
        identity = (Bucket, Key)
        if identity in self.objects:
            raise FakePreconditionFailed
        self.objects[identity] = Body

    def get_object(self, *, Bucket, Key):
        return {"Body": FakeBody(self.objects[(Bucket, Key)])}

    def delete_object(self, *, Bucket, Key):
        self.objects.pop((Bucket, Key), None)

    def get_paginator(self, operation):
        assert operation == "list_objects_v2"
        return FakePaginator(self)

    def delete_objects(self, *, Bucket, Delete):
        for item in Delete["Objects"]:
            self.objects.pop((Bucket, item["Key"]), None)


class FakeBody:
    def __init__(self, value: bytes):
        self.value = value

    def read(self) -> bytes:
        return self.value


class FakePaginator:
    def __init__(self, client: FakeS3Client):
        self.client = client

    def paginate(self, *, Bucket, Prefix):
        yield {
            "Contents": [
                {"Key": key}
                for bucket, key in self.client.objects
                if bucket == Bucket and key.startswith(Prefix)
            ]
        }


class FakePreconditionFailed(Exception):
    response = {"Error": {"Code": "PreconditionFailed"}}


def test_s3_adapter_uses_plane_prefixes_and_deletes_one_installation():
    client = FakeS3Client()
    storage = storage_module.S3ObjectStorage(
        bucket="iaido-telemetry",
        endpoint_url="http://minio:9000",
        access_key="access-key",
        secret_key="secret-key",
        region="us-east-1",
        client=client,
    )
    key = storage.object_key(
        "research",
        "00000000-0000-4000-8000-000000000001",
        "batch-1-1000-00000000-0000-4000-8000-000000000002",
    )

    storage.put_if_absent(key, b"payload")
    storage.put_if_absent(key, b"payload")
    storage.delete_installation("research", "00000000-0000-4000-8000-000000000001")

    assert key.startswith("research/")
    assert client.objects == {}
