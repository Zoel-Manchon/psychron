"""Who is asking, kept behind a port.

The device plane authenticates with client certificates and will keep doing so.
The browser cannot: client certificates in a browser mean system dialogs and
manual imports, and a dashboard nobody can open is not more secure, it is just
unused. So there are two identity planes on purpose — devices by certificate,
people by token — and this is where the second one lives.

The token implementation here is the local default. Aegis already does risk
scoring, MFA and a tamper-evident audit log over exactly this decision; slotting
it in means writing another Authenticator and changing no route.
"""

from __future__ import annotations

import hmac
import secrets
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from fastapi import Cookie, Header, HTTPException, status


@dataclass(frozen=True)
class Principal:
    """Who the request is from, and by what means it was established."""

    subject: str
    method: str


class Authenticator(Protocol):
    def authenticate(self, credential: str | None) -> Principal | None:
        """Return the principal, or None when the credential does not hold."""


class TokenAuthenticator:
    """A single shared bearer token, read from infra/.env.

    Honest about what it is: enough to stop the dashboard being wide open to
    anything that can reach the port, and no more. There are no users, no
    sessions and no revocation — that is Aegis's job, and pretending otherwise
    in a docstring would be worse than the limitation itself.
    """

    def __init__(self, token: str) -> None:
        if not token:
            raise ValueError("refusing to start with an empty API token")
        self._token = token

    def authenticate(self, credential: str | None) -> Principal | None:
        if not credential:
            return None
        scheme, _, value = credential.partition(" ")
        if scheme.lower() != "bearer":
            return None
        # Constant time: a plain == leaks the token one character at a time to
        # anyone willing to measure.
        if not hmac.compare_digest(value.strip(), self._token):
            return None
        return Principal(subject="local", method="bearer-token")


def ensure_token(env_path: Path) -> str:
    """Read the API token, minting one on first run rather than defaulting to none."""
    text = env_path.read_text(encoding="utf-8") if env_path.exists() else ""
    for line in text.splitlines():
        if line.startswith("PSYCHRON_API_TOKEN="):
            value = line.split("=", 1)[1].strip()
            if value:
                return value

    token = secrets.token_urlsafe(32)
    with env_path.open("a", encoding="utf-8") as fh:
        fh.write("\n# Bearer token for the read API. Rotate by deleting this line.\n")
        fh.write(f"PSYCHRON_API_TOKEN={token}\n")
    return token


_authenticator: Authenticator | None = None
_identity = None


def set_authenticator(auth: Authenticator) -> None:
    global _authenticator
    _authenticator = auth


def set_identity(identity) -> None:
    """The session-cookie provider. People sign in; scripts carry a token."""
    global _identity
    _identity = identity


def require_principal(authorization: str | None = Header(default=None),
                      psychron_session: str | None = Cookie(default=None)) -> Principal:
    # The cookie is tried first because it is how a person arrives. The bearer
    # token stays for the CLI and for anything scripted, where a browser session
    # makes no sense — two clients, two credentials, one guard.
    if psychron_session and _identity is not None:
        who = _identity.session_principal(psychron_session)
        if who is not None:
            return Principal(subject=who, method="session")

    if _authenticator is not None:
        principal = _authenticator.authenticate(authorization)
        if principal is not None:
            return principal

    raise HTTPException(status.HTTP_401_UNAUTHORIZED, "sign in or present a bearer token",
                        headers={"WWW-Authenticate": "Bearer"})
