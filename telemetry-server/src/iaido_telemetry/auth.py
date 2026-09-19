from __future__ import annotations

import base64
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


def browser_credential(request: Request) -> str:
    """Operator credential from either `Bearer <token>` or HTTP Basic `<any>:<token>`.

    The Basic form exists for the operator dashboard, which is opened in a browser and cannot set
    an `Authorization: Bearer` header by hand. The username is ignored and the password is
    compared to the operator token, so no second secret exists and a browser credential prompt is
    the only UI needed.
    """
    header = request.headers.get("Authorization", "")
    scheme, _, credential = header.partition(" ")
    if scheme.lower() == "basic" and credential:
        try:
            decoded = base64.b64decode(credential + "=" * (-len(credential) % 4)).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            decoded = ""
        _, _, password = decoded.partition(":")
        if password:
            return password
    if scheme.lower() == "bearer" and credential:
        return credential
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Operator credential required",
        headers={"WWW-Authenticate": 'Basic realm="Iaido telemetry", charset="UTF-8"'},
    )


def deletion_receipt_id(deletion_hash: str, plane: str) -> str:
    material = f"iaido-deletion-v1:{plane}".encode("utf-8")
    return hmac.new(deletion_hash.encode("ascii"), material, hashlib.sha256).hexdigest()
