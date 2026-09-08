"""PostgreSQL-backed local identity provider.

The default the Authenticator port resolves to. Deliberately the smallest thing
that makes the designed sign-in screens truthful rather than decorative: a
second factor that actually verifies, an attempt counter that actually counts,
and an invitation link that is actually single use.
"""

from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timedelta, timezone

import pyotp
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from ..domain.identity import (
    INVITATION_LIFETIME,
    LinkState,
    describe_attempt,
    invitation_state,
    lockout_state,
    session_expiry,
)

_hasher = PasswordHasher()


def _digest(value: str) -> str:
    """Tokens are stored hashed. A session identifier or invitation token
    readable from the database is one the database holder can replay."""
    return hashlib.sha256(value.encode()).hexdigest()


class PostgresIdentity:
    def __init__(self, dsn: str) -> None:
        self._pool = ConnectionPool(dsn, min_size=1, max_size=4, open=True,
                                    kwargs={"autocommit": True, "row_factory": dict_row})

    def close(self) -> None:
        self._pool.close()

    # ── accounts ────────────────────────────────────────────────────────────

    def account_count(self) -> int:
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute("SELECT count(*) AS n FROM account WHERE disabled_at IS NULL")
            return cur.fetchone()["n"]

    def _account(self, identifier: str) -> dict | None:
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute("SELECT * FROM account WHERE identifier = %s AND disabled_at IS NULL",
                        (identifier,))
            return cur.fetchone()

    def _record(self, identifier: str, stage: str, outcome: str,
                ip: str | None, ua: str | None) -> None:
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "INSERT INTO login_attempt (identifier, ip, user_agent, stage, outcome) "
                "VALUES (%s, %s, %s, %s, %s)", (identifier, ip, ua, stage, outcome))

    def lockout(self, identifier: str):
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                # Both outcomes count. Locking only known accounts would make
                # the lockout itself an enumeration oracle: the message is the
                # same either way, but "this one starts refusing after three"
                # answers the question the message refused to.
                "SELECT at FROM login_attempt WHERE identifier = %s "
                "AND outcome IN ('bad_credential', 'unknown_account') "
                "AND at > now() - interval '15 minutes' "
                "ORDER BY at DESC", (identifier,))
            return lockout_state([r["at"] for r in cur.fetchall()],
                                 datetime.now(timezone.utc))

    # ── stage one: password ─────────────────────────────────────────────────

    def verify_password(self, identifier: str, password: str,
                        ip: str | None, ua: str | None) -> tuple[bool, str]:
        state = self.lockout(identifier)
        if state.locked:
            self._record(identifier, "password", "locked", ip, ua)
            return False, "locked"

        account = self._account(identifier)
        if account is None or not account["password_hash"]:
            # Hash anyway, so a missing account takes the same time as a wrong
            # password. Otherwise the response time enumerates who exists.
            _hasher.hash(password)
            self._record(identifier, "password", "unknown_account", ip, ua)
            return False, "bad_credential"

        try:
            _hasher.verify(account["password_hash"], password)
        except VerifyMismatchError:
            self._record(identifier, "password", "bad_credential", ip, ua)
            return False, "bad_credential"

        self._record(identifier, "password", "ok", ip, ua)
        return True, "totp" if account["totp_secret"] else "ok"

    # ── stage two: the authenticator ────────────────────────────────────────

    def verify_totp(self, identifier: str, code: str,
                    ip: str | None, ua: str | None) -> bool:
        state = self.lockout(identifier)
        if state.locked:
            return False
        account = self._account(identifier)
        if account is None or not account["totp_secret"]:
            return False

        # One period of tolerance either way: a phone whose clock is a few
        # seconds out is the common case, not an attack.
        ok = pyotp.TOTP(account["totp_secret"]).verify(code, valid_window=1)
        self._record(identifier, "totp", "ok" if ok else "bad_credential", ip, ua)
        return ok

    def verify_recovery(self, identifier: str, code: str,
                        ip: str | None, ua: str | None) -> bool:
        account = self._account(identifier)
        if account is None:
            return False
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "UPDATE recovery_code SET used_at = now() "
                "WHERE account_id = %s AND code_hash = %s AND used_at IS NULL "
                "RETURNING code_hash",
                (account["account_id"], _digest(code.strip().lower())))
            ok = cur.fetchone() is not None
        self._record(identifier, "recovery", "ok" if ok else "bad_credential", ip, ua)
        return ok

    def recovery_remaining(self, identifier: str) -> tuple[int, int]:
        account = self._account(identifier)
        if account is None:
            return (0, 0)
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "SELECT count(*) FILTER (WHERE used_at IS NULL) AS unused, count(*) AS total "
                "FROM recovery_code WHERE account_id = %s", (account["account_id"],))
            r = cur.fetchone()
            return (r["unused"], r["total"])

    def attempt_context(self, identifier: str, ip: str | None, ua: str | None):
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "SELECT ip, at, user_agent FROM login_attempt "
                "WHERE identifier = %s AND outcome = 'ok' AND stage = 'password' "
                "ORDER BY at DESC LIMIT 200", (identifier,))
            rows = cur.fetchall()
        history = [(str(r["ip"]) if r["ip"] else None, r["at"]) for r in rows]
        agents = {r["user_agent"] for r in rows if r["user_agent"]}
        return describe_attempt(ip, history, ua, agents)

    # ── sessions ────────────────────────────────────────────────────────────

    def open_session(self, identifier: str, ip: str | None, ua: str | None,
                     pending: bool = False, minutes: int | None = None) -> str:
        account = self._account(identifier)
        if account is None:
            raise ValueError("no such account")
        token = secrets.token_urlsafe(32)
        with self._pool.connection() as c, c.cursor() as cur:
            now = datetime.now(timezone.utc)
            expires = now + timedelta(minutes=minutes) if minutes else session_expiry(now)
            cur.execute(
                "INSERT INTO session (session_id, account_id, expires_at, ip, user_agent, pending) "
                "VALUES (%s, %s, %s, %s, %s, %s)",
                (_digest(token), account["account_id"], expires, ip, ua, pending))
        return token

    def session_principal(self, token: str, allow_pending: bool = False) -> str | None:
        """A pending session grants nothing until the second factor is proved.

        The default excludes it, so a half-finished sign-in can never be
        mistaken for a finished one by a route that forgot to ask.
        """
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "SELECT a.identifier FROM session s JOIN account a USING (account_id) "
                "WHERE s.session_id = %s AND s.revoked_at IS NULL "
                "AND s.expires_at > now() AND a.disabled_at IS NULL "
                "AND (pending = false OR %s)",
                (_digest(token), allow_pending))
            row = cur.fetchone()
            return row["identifier"] if row else None

    def promote_session(self, token: str) -> str | None:
        """Second factor proved: the pending session becomes a real one."""
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "UPDATE session SET pending = false, expires_at = %s "
                "WHERE session_id = %s AND pending = true AND expires_at > now() "
                "RETURNING account_id",
                (session_expiry(datetime.now(timezone.utc)), _digest(token)))
            if cur.fetchone() is None:
                return None
        return self.session_principal(token)

    def revoke_session(self, token: str) -> None:
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute("UPDATE session SET revoked_at = now() WHERE session_id = %s",
                        (_digest(token),))

    # ── invitations ─────────────────────────────────────────────────────────

    def issue_invitation(self, identifier: str, issued_by: str | None = None) -> str:
        token = secrets.token_urlsafe(24)
        by = self._account(issued_by)["account_id"] if issued_by and self._account(issued_by) else None
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "INSERT INTO invitation (token_hash, identifier, issued_by, expires_at) "
                "VALUES (%s, %s, %s, %s)",
                (_digest(token), identifier, by,
                 datetime.now(timezone.utc) + INVITATION_LIFETIME))
        return token

    def inspect_invitation(self, token: str, mark_open: bool = False) -> dict | None:
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute("SELECT * FROM invitation WHERE token_hash = %s", (_digest(token),))
            inv = cur.fetchone()
            if inv is None:
                return None
            state = invitation_state(inv["expires_at"], inv["opened_at"],
                                     inv["consumed_at"], datetime.now(timezone.utc))
            if mark_open and state is LinkState.ISSUED:
                cur.execute("UPDATE invitation SET opened_at = now() WHERE token_hash = %s",
                            (_digest(token),))
                state = LinkState.OPEN
        return {"identifier": inv["identifier"], "state": state.value,
                "expires_at": inv["expires_at"]}

    def consume_invitation(self, token: str, password: str) -> tuple[str, str, list[str]]:
        """Creates the account and returns identifier, TOTP secret and recovery codes."""
        info = self.inspect_invitation(token)
        if info is None or info["state"] not in ("issued", "open"):
            raise ValueError(info["state"] if info else "unknown")

        secret = pyotp.random_base32()
        codes = [secrets.token_hex(4) for _ in range(10)]
        with self._pool.connection() as c, c.cursor() as cur:
            cur.execute(
                "INSERT INTO account (identifier, password_hash, totp_secret, is_owner) "
                "VALUES (%s, %s, %s, %s) "
                "ON CONFLICT (identifier) DO UPDATE SET password_hash = EXCLUDED.password_hash, "
                "totp_secret = EXCLUDED.totp_secret RETURNING account_id",
                (info["identifier"], _hasher.hash(password), secret,
                 self.account_count() == 0))
            account_id = cur.fetchone()["account_id"]
            for code in codes:
                cur.execute(
                    "INSERT INTO recovery_code (account_id, code_hash) VALUES (%s, %s) "
                    "ON CONFLICT DO NOTHING", (account_id, _digest(code)))
            # Consumed only once the account exists: a link burned by a failed
            # write would strand whoever it was issued to.
            cur.execute("UPDATE invitation SET consumed_at = now() WHERE token_hash = %s",
                        (_digest(token),))
        return info["identifier"], secret, codes
