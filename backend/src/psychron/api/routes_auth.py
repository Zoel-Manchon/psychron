"""Sign-in, second factor and invitation.

The browser never holds a credential a script can read: the session lives in an
httpOnly cookie, which also means the WebSocket authenticates on its own without
a token in the query string — the one piece of debt the token scheme left behind.
"""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Cookie, HTTPException, Request, Response, status
from pydantic import BaseModel, Field

from ..adapters.identity_pg import PostgresIdentity
from ..domain.identity import MAX_ATTEMPTS, TOTP_PERIOD, seconds_into_totp_period

SESSION_COOKIE = "psychron_session"
PENDING_COOKIE = "psychron_pending"


class LoginBody(BaseModel):
    identifier: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=1, max_length=400)


class CodeBody(BaseModel):
    code: str = Field(min_length=4, max_length=64)


class AcceptBody(BaseModel):
    token: str = Field(min_length=8, max_length=200)
    password: str = Field(min_length=12, max_length=400)


def _client(request: Request) -> tuple[str | None, str | None]:
    ip = request.client.host if request.client else None
    return ip, request.headers.get("user-agent")


def _set(request: Request, response: Response, name: str, value: str,
         seconds: int) -> None:
    # Secure is read off the request rather than from a setting someone has to
    # remember to flip. A flag defaulting to on breaks development, because a
    # browser silently refuses to store a Secure cookie over plain HTTP and the
    # sign-in just never completes; a flag defaulting to off ships production
    # without it. The scheme already knows the answer.
    response.set_cookie(
        name, value, max_age=seconds, httponly=True, samesite="strict",
        secure=request.url.scheme == "https", path="/")


def build_router(identity: PostgresIdentity) -> APIRouter:
    r = APIRouter(prefix="/api/auth")

    @r.get("/state")
    def state(request: Request,
              psychron_session: str | None = Cookie(default=None)) -> dict:
        """What the sign-in screen needs before showing anything: whether an
        account exists at all, and whether this browser is already signed in."""
        who = identity.session_principal(psychron_session) if psychron_session else None
        return {
            "signed_in_as": who,
            "first_run": identity.account_count() == 0,
            "provider": "local",
            "session_hours": 12,
        }

    @r.post("/login")
    def login(body: LoginBody, response: Response, request: Request) -> dict:
        ip, ua = _client(request)
        ok, outcome = identity.verify_password(body.identifier, body.password, ip, ua)
        lock = identity.lockout(body.identifier)

        if not ok:
            if outcome == "locked":
                raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, {
                    "error": "locked",
                    "until": lock.until.isoformat() if lock.until else None,
                })
            # The same message whether the account exists or not: a different
            # one would let anyone enumerate who has an account here.
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, {
                "error": "bad_credential",
                "attempts_remaining": lock.attempts_remaining,
            })

        if outcome == "ok":
            token = identity.open_session(body.identifier, ip, ua)
            _set(request, response, SESSION_COOKIE, token, 12 * 3600)
            return {"next": "done", "identifier": body.identifier}

        # First factor proved, second not. The pending session is what stops the
        # next step from trusting an identifier the client hands back.
        pending = identity.open_session(body.identifier, ip, ua, pending=True, minutes=5)
        _set(request, response, PENDING_COOKIE, pending, 300)

        ctx = identity.attempt_context(body.identifier, ip, ua)
        unused, total = identity.recovery_remaining(body.identifier)
        return {
            "next": "totp",
            "identifier": body.identifier,
            "attempts_remaining": lock.attempts_remaining,
            "max_attempts": MAX_ATTEMPTS,
            "period": TOTP_PERIOD,
            "elapsed": seconds_into_totp_period(datetime.now(timezone.utc)),
            "recovery": {"unused": unused, "total": total},
            "context": {
                "ip": ctx.ip,
                "location_is_new": ctx.location_is_new,
                "usual_location": ctx.usual_location,
                "previous_successes": ctx.previous_successes,
                "browser_seen_before": ctx.browser_seen_before,
            },
        }

    def _finish(response: Response, request: Request, pending_token: str,
                verify) -> dict:
        who = identity.session_principal(pending_token, allow_pending=True)
        if who is None:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED,
                                {"error": "no_pending_session"})
        ip, ua = _client(request)
        if not verify(who, ip, ua):
            lock = identity.lockout(who)
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, {
                "error": "bad_code",
                "attempts_remaining": lock.attempts_remaining,
            })

        identity.promote_session(pending_token)
        _set(request, response, SESSION_COOKIE, pending_token, 12 * 3600)
        response.delete_cookie(PENDING_COOKIE, path="/")
        return {"next": "done", "identifier": who}

    @r.post("/totp")
    def totp(body: CodeBody, response: Response, request: Request,
             psychron_pending: str | None = Cookie(default=None)) -> dict:
        if not psychron_pending:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, {"error": "no_pending_session"})
        return _finish(response, request, psychron_pending,
                       lambda who, ip, ua: identity.verify_totp(who, body.code, ip, ua))

    @r.post("/recovery")
    def recovery(body: CodeBody, response: Response, request: Request,
                 psychron_pending: str | None = Cookie(default=None)) -> dict:
        if not psychron_pending:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, {"error": "no_pending_session"})
        return _finish(response, request, psychron_pending,
                       lambda who, ip, ua: identity.verify_recovery(who, body.code, ip, ua))

    @r.post("/logout")
    def logout(response: Response,
               psychron_session: str | None = Cookie(default=None)) -> dict:
        if psychron_session:
            identity.revoke_session(psychron_session)
        response.delete_cookie(SESSION_COOKIE, path="/")
        response.delete_cookie(PENDING_COOKIE, path="/")
        return {"ok": True}

    # ── invitation ──────────────────────────────────────────────────────────

    @r.get("/invitation")
    def inspect(token: str) -> dict:
        info = identity.inspect_invitation(token, mark_open=True)
        if info is None:
            # An unknown token and an expired one are reported the same way, so
            # the endpoint cannot be used to confirm which links ever existed.
            return {"state": "expired", "identifier": None}
        return info

    @r.post("/invitation")
    def accept(body: AcceptBody, response: Response, request: Request) -> dict:
        try:
            identifier, secret, codes = identity.consume_invitation(body.token, body.password)
        except ValueError as exc:
            raise HTTPException(status.HTTP_409_CONFLICT, {"error": str(exc)}) from exc

        ip, ua = _client(request)
        pending = identity.open_session(identifier, ip, ua, pending=True, minutes=10)
        _set(request, response, PENDING_COOKIE, pending, 600)
        # The secret and the codes are shown once, here, and never again: they
        # exist in the response and in the reader's hands, nowhere else.
        return {"identifier": identifier, "totp_secret": secret,
                "otpauth": f"otpauth://totp/psychron:{identifier}?secret={secret}&issuer=psychron",
                "recovery_codes": codes}

    return r
