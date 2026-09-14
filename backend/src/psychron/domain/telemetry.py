"""Contract v1 parsing and the timestamp resolution rule.

The domain core: no MQTT, no database, no clock of its own. Everything here is a
pure function of its arguments, which is what makes the interesting decision —
what instant a reading actually happened at — testable without a broker.

See ../../../docs/CONTRACT.md for the wire format these types mirror.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

CONTRACT_VERSION = 1

# Quality bits, mirroring firmware/psychron_node/config.h.
Q_CLOCK_UNSYNCED = 0x01
Q_REPLAYED = 0x02
Q_PRIOR_FAILED = 0x04
Q_OUT_OF_RANGE = 0x08
Q_INTERVAL_DRIFT = 0x10

# Bits ingestion adds; they live above the firmware's range so the two can never
# collide as either side grows.
Q_TIME_FROM_ANCHOR = 0x0100  # placed via the boot anchor, not the device clock
Q_TIME_FROM_ARRIVAL = 0x0200  # no anchor available; arrival time was all we had

# How far a device clock may sit from arrival before it stops being believed.
CLOCK_TOLERANCE = timedelta(minutes=5)


class InvalidMessage(Exception):
    """Rejected before it reaches the database. Carries why, for the audit table."""


@dataclass(frozen=True)
class TelemetryMessage:
    """A parsed message. Still a claim: nothing here has been cross-checked yet."""

    version: int
    device_id: str
    firmware: str
    boot_id: int
    seq: int
    device_time: datetime | None
    uptime_ms: int
    temperature_c: float | None
    humidity_pct: float | None
    quality: int


@dataclass(frozen=True)
class ResolvedReading:
    """A message with an authoritative instant attached, ready to store."""

    time: datetime
    device_id: str
    boot_id: int
    seq: int
    device_time: datetime | None
    received_at: datetime
    uptime_ms: int
    temperature_c: float | None
    humidity_pct: float | None
    quality: int
    firmware: str


def _require(cond: bool, reason: str) -> None:
    if not cond:
        raise InvalidMessage(reason)


def _optional_float(raw: object, field: str) -> float | None:
    if raw is None:
        return None
    _require(isinstance(raw, (int, float)) and not isinstance(raw, bool),
             f"{field} must be a number or null")
    value = float(raw)  # type: ignore[arg-type]
    # NaN and infinity survive json.loads and would poison every aggregate they
    # touch. A sensor that cannot answer sends null, so these are malformed.
    _require(math.isfinite(value), f"{field} must be finite")
    return value


def _int_field(data: dict, key: str, lo: int, hi: int) -> int:
    raw = data.get(key)
    _require(isinstance(raw, int) and not isinstance(raw, bool), f"{key} must be an integer")
    _require(lo <= raw <= hi, f"{key} out of range")  # type: ignore[operator]
    return int(raw)  # type: ignore[arg-type]


def parse_device_clock(data: dict) -> datetime | None:
    """The device's claimed instant: `ts` seconds plus the optional `ms` within them.

    Shared by every contract version, like the resolution rule below, so the two
    cannot come to disagree about what a device said the time was.

    `ms` was added after both contracts were in use. Integer seconds cap how well
    two nodes can be compared at all: each claim could be up to a second early, so
    nothing finer than that could be said about whether two readings coincided.
    It is a separate field rather than a change to `ts`, because a field must never
    change meaning under a version number that already has data behind it, and a
    message without it is still valid — it is simply known to the second.
    """
    ts_raw = data.get("ts")
    ms_raw = data.get("ms")
    if ts_raw is None:
        # Milliseconds of a second nobody knows are not a partial clock; they are
        # a sign the device's clock logic is broken, and worth rejecting loudly.
        _require(ms_raw is None, "ms without ts")
        return None

    _require(isinstance(ts_raw, int) and not isinstance(ts_raw, bool), "ts must be an integer or null")
    _require(0 < ts_raw < 4102444800, "ts outside a plausible epoch range")  # < year 2100

    ms = 0
    if ms_raw is not None:
        _require(isinstance(ms_raw, int) and not isinstance(ms_raw, bool), "ms must be an integer")
        _require(0 <= ms_raw <= 999, "ms out of range")
        ms = ms_raw
    # Integer arithmetic on both parts: a float epoch such as ts + ms / 1000 would
    # round a microsecond or two, which is exactly the precision being added.
    return datetime.fromtimestamp(ts_raw, tz=timezone.utc) + timedelta(milliseconds=ms)


def parse(payload: bytes) -> TelemetryMessage:
    """Turn a raw MQTT payload into a message, or say why it cannot be one."""
    try:
        data = json.loads(payload)
    except (ValueError, UnicodeDecodeError) as exc:
        raise InvalidMessage(f"not JSON: {exc}") from exc

    _require(isinstance(data, dict), "payload is not a JSON object")

    version = data.get("v")
    _require(version == CONTRACT_VERSION, f"unsupported contract version {version!r}")

    device_id = data.get("dev")
    _require(isinstance(device_id, str) and 0 < len(device_id) <= 64, "dev must be a short string")

    firmware = data.get("fw")
    _require(isinstance(firmware, str) and 0 < len(firmware) <= 32, "fw must be a short string")

    device_time = parse_device_clock(data)

    return TelemetryMessage(
        version=version,
        device_id=device_id,
        firmware=firmware,
        boot_id=_int_field(data, "boot", 0, 0xFFFFFFFF),
        seq=_int_field(data, "seq", 0, 0xFFFFFFFF),
        device_time=device_time,
        uptime_ms=_int_field(data, "up", 0, 0xFFFFFFFF),
        temperature_c=_optional_float(data.get("t"), "t"),
        humidity_pct=_optional_float(data.get("h"), "h"),
        quality=_int_field(data, "q", 0, 0xFFFF),
    )


class BootAnchors:
    """Wall-clock instant each boot began, so readings taken without a synced
    clock can still be placed.

    This is what `up` in the contract is for. A device that boots with no network
    measures uptime correctly from the first millisecond but has no idea of the
    date; once one message from that boot arrives with a clock worth believing,
    every other message from the same boot can be placed exactly — including the
    ones already buffered on flash, replayed hours later.
    """

    def __init__(self) -> None:
        self._anchors: dict[tuple[str, int], datetime] = {}

    def observe(self, msg: TelemetryMessage, trusted_time: datetime) -> None:
        key = (msg.device_id, msg.boot_id)
        if key not in self._anchors:
            self._anchors[key] = trusted_time - timedelta(milliseconds=msg.uptime_ms)

    def seed(self, device_id: str, boot_id: int, boot_epoch: datetime) -> None:
        self._anchors[(device_id, boot_id)] = boot_epoch

    def instant_for(self, msg: TelemetryMessage) -> datetime | None:
        anchor = self._anchors.get((msg.device_id, msg.boot_id))
        if anchor is None:
            return None
        return anchor + timedelta(milliseconds=msg.uptime_ms)


def resolve_instant(msg, received_at: datetime,
                    anchors: BootAnchors) -> tuple[datetime, int]:
    """The authoritative instant for any message, and the quality that records
    how it was arrived at.

    Order of preference: the device clock when it can be believed, then the boot
    anchor, then arrival time. Every fallback sets a quality bit, so a later
    analysis can tell a measured timestamp from an inferred one instead of
    finding a column of instants that all look equally authoritative.

    Only the envelope is read — `device_id`, `boot_id`, `uptime_ms`,
    `device_time`, `quality` — which every contract version shares. That is the
    point of keeping this separate from `resolve`: a second message type gets the
    same rule by construction, rather than a copy of it that drifts.
    """
    quality = msg.quality
    clock_claimed = msg.device_time is not None and not (msg.quality & Q_CLOCK_UNSYNCED)

    if clock_claimed and abs(msg.device_time - received_at) <= CLOCK_TOLERANCE:  # type: ignore[operator]
        instant = msg.device_time
        anchors.observe(msg, instant)  # type: ignore[arg-type]
    else:
        # A replayed reading arrives long after it was taken, so arrival time is
        # meaningless for it; the anchor is the only honest source.
        from_anchor = anchors.instant_for(msg)
        if from_anchor is not None:
            instant = from_anchor
            quality |= Q_TIME_FROM_ANCHOR
        else:
            instant = received_at
            quality |= Q_TIME_FROM_ARRIVAL

    return instant, quality  # type: ignore[return-value]


def resolve(msg: TelemetryMessage, received_at: datetime,
            anchors: BootAnchors) -> ResolvedReading:
    """Attach the authoritative instant to a v1 reading."""
    instant, quality = resolve_instant(msg, received_at, anchors)
    return ResolvedReading(
        time=instant,  # type: ignore[arg-type]
        device_id=msg.device_id,
        boot_id=msg.boot_id,
        seq=msg.seq,
        device_time=msg.device_time,
        received_at=received_at,
        uptime_ms=msg.uptime_ms,
        temperature_c=msg.temperature_c,
        humidity_pct=msg.humidity_pct,
        quality=quality,
        firmware=msg.firmware,
    )
