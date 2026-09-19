from __future__ import annotations

import os
import shutil
import threading
from pathlib import Path
from typing import Protocol


class ObjectConflictError(Exception):
    pass


PLANES = frozenset({"diagnostics", "research"})


class ObjectStorage(Protocol):
    def object_key(self, plane: str, installation_id: str, batch_id: str) -> str: ...

    def put_if_absent(self, key: str, payload: bytes) -> None: ...

    def get(self, key: str) -> bytes: ...

    def delete(self, key: str) -> None: ...

    def delete_installation(self, plane: str, installation_id: str) -> None: ...


def object_key(plane: str, installation_id: str, batch_id: str) -> str:
    if plane not in PLANES:
        raise ValueError("unknown telemetry plane")
    return f"{plane}/{installation_id}/{batch_id}.json"


class LocalObjectStorage:
    """Filesystem adapter for tests; production uses S3ObjectStorage."""

    def __init__(self, root: Path):
        self.root = root.resolve()
        self._lock = threading.Lock()

    def object_key(self, plane: str, installation_id: str, batch_id: str) -> str:
        return object_key(plane, installation_id, batch_id)

    def put_if_absent(self, key: str, payload: bytes) -> None:
        path = self._path(key)
        with self._lock:
            if path.exists():
                if path.read_bytes() != payload:
                    raise ObjectConflictError(key)
                return
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = path.with_name(f".{path.name}.{os.urandom(8).hex()}.tmp")
            try:
                temporary.write_bytes(payload)
                os.replace(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)

    def get(self, key: str) -> bytes:
        return self._path(key).read_bytes()

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)

    def delete_installation(self, plane: str, installation_id: str) -> None:
        if plane not in PLANES:
            raise ValueError("unknown telemetry plane")
        directory = self._path(f"{plane}/{installation_id}")
        if directory.exists():
            shutil.rmtree(directory)

    def _path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        if path != self.root and self.root not in path.parents:
            raise ValueError("object key escapes storage root")
        return path


class S3ObjectStorage:
    def __init__(
        self,
        *,
        bucket: str,
        endpoint_url: str,
        access_key: str,
        secret_key: str,
        region: str,
        client=None,
    ):
        self.bucket = bucket
        if client is None:
            import boto3

            client = boto3.client(
                "s3",
                endpoint_url=endpoint_url,
                aws_access_key_id=access_key,
                aws_secret_access_key=secret_key,
                region_name=region,
            )
        self.client = client

    def object_key(self, plane: str, installation_id: str, batch_id: str) -> str:
        return object_key(plane, installation_id, batch_id)

    def put_if_absent(self, key: str, payload: bytes) -> None:
        try:
            self.client.put_object(
                Bucket=self.bucket,
                Key=key,
                Body=payload,
                IfNoneMatch="*",
            )
        except Exception as error:
            code = getattr(error, "response", {}).get("Error", {}).get("Code")
            if code not in {"PreconditionFailed", "412"}:
                raise
            existing = self.client.get_object(Bucket=self.bucket, Key=key)[
                "Body"
            ].read()
            if existing != payload:
                raise ObjectConflictError(key) from error

    def get(self, key: str) -> bytes:
        return self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()

    def delete(self, key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=key)

    def delete_installation(self, plane: str, installation_id: str) -> None:
        prefix = (
            object_key(plane, installation_id, "placeholder").rsplit("/", 1)[0] + "/"
        )
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self.bucket, Prefix=prefix):
            objects = [{"Key": item["Key"]} for item in page.get("Contents", [])]
            if objects:
                self.client.delete_objects(
                    Bucket=self.bucket,
                    Delete={"Objects": objects, "Quiet": True},
                )
