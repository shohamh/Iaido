from __future__ import annotations

import hashlib
import hmac
import secrets

from fastapi import HTTPException, Request, status


def issue_credential() -> str:
    return secrets.token_urlsafe(32)


def credential_hash(credential: str) -> str:
    return hashlib.sha256(credential.encode("utf-8")).hexdigest()


def credentials_match(candidate: str, expected: str) -> bool:
    return hmac.compare_digest(candidate, expected)


def bearer_credential(request: Request) -> str:
    scheme, _, credential = request.headers.get("Authorization", "").partition(" ")
    if scheme.lower() != "bearer" or not credential:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Bearer credential required",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return credential


def deletion_receipt_id(deletion_hash: str, plane: str) -> str:
    material = f"iaido-deletion-v1:{plane}".encode("utf-8")
    return hmac.new(deletion_hash.encode("ascii"), material, hashlib.sha256).hexdigest()
