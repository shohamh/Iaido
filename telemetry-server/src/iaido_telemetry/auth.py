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


def basic_or_bearer(request: Request) -> tuple[str | None, str]:
    """Split an operator credential into `(username, secret)`.

    `Bearer <token>` yields `(None, token)`, so the operator API keeps its single-token contract.
    HTTP Basic `<username>:<password>` yields `(username, password)`, which the dashboard uses for
    its own login; a browser sends Basic natively, so there is no session, cookie, or login form
    to secure. The username is only meaningful for Basic - a Bearer request never carries one.
    """
    header = request.headers.get("Authorization", "")
    scheme, _, credential = header.partition(" ")
    if scheme.lower() == "basic" and credential:
        try:
            decoded = base64.b64decode(credential + "=" * (-len(credential) % 4)).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            decoded = ""
        username, separator, password = decoded.partition(":")
        if separator and username and password:
            return username, password
    elif scheme.lower() == "bearer" and credential:
        return None, credential
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Operator credential required",
        headers={"WWW-Authenticate": 'Basic realm="Iaido telemetry", charset="UTF-8"'},
    )


def deletion_receipt_id(deletion_hash: str, plane: str) -> str:
    material = f"iaido-deletion-v1:{plane}".encode("utf-8")
    return hmac.new(deletion_hash.encode("ascii"), material, hashlib.sha256).hexdigest()
