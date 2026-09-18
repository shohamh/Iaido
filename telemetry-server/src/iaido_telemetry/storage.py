from __future__ import annotations

import os
import shutil
from pathlib import Path


class ObjectConflictError(Exception):
    pass


class LocalObjectStorage:
    PLANES = frozenset({"diagnostics", "research"})

    def __init__(self, root: Path):
        self.root = root.resolve()

    def object_key(self, plane: str, installation_id: str, batch_id: str) -> str:
        self._check_plane(plane)
        return f"{plane}/{installation_id}/{batch_id}.json"

    def put_if_absent(self, key: str, payload: bytes) -> None:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
        except FileExistsError:
            if path.read_bytes() != payload:
                raise ObjectConflictError(key)
            return
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)

    def delete_installation(self, plane: str, installation_id: str) -> None:
        self._check_plane(plane)
        directory = self._path(f"{plane}/{installation_id}")
        if directory.exists():
            shutil.rmtree(directory)

    def _path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        if path != self.root and self.root not in path.parents:
            raise ValueError("object key escapes storage root")
        return path

    def _check_plane(self, plane: str) -> None:
        if plane not in self.PLANES:
            raise ValueError("unknown telemetry plane")
