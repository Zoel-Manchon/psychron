"""Identity rules, as pure functions of their arguments.

Everything here is decidable without a database: how long a lockout lasts, how
many attempts remain, whether an invitation link is still usable, and whether a
sign-in came from somewhere unusual. That is what makes the interesting parts
testable, and the interesting parts are exactly the ones a screen shows.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum

MAX_ATTEMPTS = 3
LOCKOUT = timedelta(minutes=15)
SESSION_LIFETIME = timedelta(hours=12)
INVITATION_LIFETIME = timedelta(days=7)
TOTP_PERIOD = 30


class LinkState(str, Enum):
    """The three states the design draws, distinguished by shape and position
    rather than by colour: an empty square, a struck one, a filled one."""

    ISSUED = "issued"      # created, never opened
    OPEN = "open"          # opened, still usable
    CONSUMED = "consumed"  # already used
    EXPIRED = "expired"    # ran out of time before being used


@dataclass(frozen=True)
class Lockout:
    locked: bool
    attempts_remaining: int
    until: datetime | None


def lockout_state(failures: list[datetime], now: datetime) -> Lockout:
    """Failures inside the window count; anything older has been forgiven.

    Counting only recent failures matters: a lockout that tallies every mistake
    ever made turns into a permanent one for anyone who has owned the account
    long enough.
    """
    recent = [f for f in failures if now - f < LOCKOUT]
    if len(recent) >= MAX_ATTEMPTS:
        return Lockout(True, 0, max(recent) + LOCKOUT)
    return Lockout(False, MAX_ATTEMPTS - len(recent), None)


def invitation_state(expires_at: datetime, opened_at: datetime | None,
                     consumed_at: datetime | None, now: datetime) -> LinkState:
    if consumed_at is not None:
        return LinkState.CONSUMED
    if now >= expires_at:
        return LinkState.EXPIRED
    return LinkState.OPEN if opened_at is not None else LinkState.ISSUED


def session_expiry(now: datetime) -> datetime:
    return now + SESSION_LIFETIME


@dataclass(frozen=True)
class AttemptContext:
    """What the second-factor screen shows about where the attempt came from.

    Presented as a fact with the evidence beside it, never as an alarm: the
    reader is the one who knows whether it was them.
    """

    ip: str | None
    location_is_new: bool
    usual_location: str | None
    previous_successes: int
    browser_seen_before: bool


def describe_attempt(ip: str | None, history: list[tuple[str | None, datetime]],
                     user_agent: str | None,
                     known_agents: set[str]) -> AttemptContext:
    ips = [h[0] for h in history if h[0]]
    usual = max(set(ips), key=ips.count) if ips else None
    return AttemptContext(
        ip=ip,
        location_is_new=bool(ip) and ip not in ips,
        usual_location=usual,
        previous_successes=len(history),
        browser_seen_before=bool(user_agent) and user_agent in known_agents,
    )


def seconds_into_totp_period(now: datetime) -> int:
    """How far through the current code's life we are.

    The design draws this as a bar that empties with scale marks, and labels it
    'the code rotates, the session does not expire' — the distinction matters,
    because a bar running out otherwise reads as losing your place.
    """
    return int(now.timestamp()) % TOTP_PERIOD
