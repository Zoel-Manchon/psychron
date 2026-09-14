"""Contract v2: window summaries from a multi-sensor node.

The envelope is v1's, and so is the timestamp rule — `resolve_instant` is imported,
not re-implemented. What differs is the body: a set of optional measurement groups,
each validated against a closed schema.

See ../../../docs/CONTRACT-v2.md.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, fields
from datetime import datetime, timezone

from .telemetry import BootAnchors, InvalidMessage, parse_device_clock, resolve_instant

CONTRACT_VERSION = 2

# JSON numbers are exact up to 2^53; beyond that a value can change silently on
# the way through any parser that uses doubles, which is most of them.
MAX_SAFE_INT = 2**53 - 1

# group -> field -> (column, lo, hi, hi_inclusive)
#
# One table is both the validator and the mapping to storage, so a field cannot be
# accepted on the wire and then quietly have nowhere to go.
SCHEMA: dict[str, dict[str, tuple[str, float, float, bool]]] = {
    "baro":  {"hpa":       ("pressure_hpa",    300.0, 1100.0,   True)},
    "light": {"lux":       ("illuminance_lux", 0.0,   200000.0, True)},
    "sound": {"rms_dbfs":  ("sound_rms_dbfs",  -160.0, 0.0,     True),
              "peak_dbfs": ("sound_peak_dbfs", -160.0, 0.0,     True)},
    "accel": {"rms":       ("accel_rms",       0.0,   160.0,    True),
              "peak":      ("accel_peak",      0.0,   160.0,    True)},
    "gyro":  {"rms":       ("gyro_rms",        0.0,   40.0,     True),
              "peak":      ("gyro_peak",       0.0,   40.0,     True)},
    "mag":   {"ut":        ("magnetic_ut",     0.0,   2000.0,   True),
              # A heading of 360 is a heading of 0 written differently; accepting
              # both would give one direction two representations in the data.
              "heading":   ("heading_deg",     0.0,   360.0,    False)},
    "batt":  {"c":         ("battery_temp_c",  -40.0, 100.0,    True)},
}

ENVELOPE = {"v", "dev", "fw", "boot", "seq", "ts", "ms", "up", "win", "q"}


@dataclass(frozen=True)
class Measurements:
    """Every quantity v2 can carry. None means no sample in the window."""

    pressure_hpa: float | None = None
    illuminance_lux: float | None = None
    sound_rms_dbfs: float | None = None
    sound_peak_dbfs: float | None = None
    accel_rms: float | None = None
    accel_peak: float | None = None
    gyro_rms: float | None = None
    gyro_peak: float | None = None
    magnetic_ut: float | None = None
    heading_deg: float | None = None
    battery_temp_c: float | None = None

    def present(self) -> int:
        return sum(1 for f in fields(self) if getattr(self, f.name) is not None)


@dataclass(frozen=True)
class SampleMessage:
    """A parsed v2 message. Carries the envelope under the same names as v1's, so
    `BootAnchors` and `resolve_instant` accept it without knowing which it is."""

    version: int
    device_id: str
    firmware: str
    boot_id: int
    seq: int
    device_time: datetime | None
    uptime_ms: int
    window_ms: int
    quality: int
    measurements: Measurements


@dataclass(frozen=True)
class ResolvedSample:
    time: datetime
    device_id: str
    boot_id: int
    seq: int
    device_time: datetime | None
    received_at: datetime
    uptime_ms: int
    window_ms: int
    quality: int
    firmware: str
    measurements: Measurements


def _require(cond: bool, reason: str) -> None:
    if not cond:
        raise InvalidMessage(reason)


def _int(data: dict, key: str, lo: int, hi: int) -> int:
    raw = data.get(key)
    _require(isinstance(raw, int) and not isinstance(raw, bool), f"{key} must be an integer")
    _require(lo <= raw <= hi, f"{key} out of range")  # type: ignore[operator]
    return int(raw)  # type: ignore[arg-type]


def _measurement(group: str, name: str, raw: object) -> tuple[str, float]:
    column, lo, hi, hi_inclusive = SCHEMA[group][name]
    where = f"{group}.{name}"
    # Inside a present group every field is a number. Null here would be a second
    # way of saying "no data", and two ways of saying one thing is how a query
    # ends up counting only one of them.
    _require(raw is not None, f"{where} is null; omit the group instead")
    _require(isinstance(raw, (int, float)) and not isinstance(raw, bool),
             f"{where} must be a number")
    value = float(raw)  # type: ignore[arg-type]
    _require(math.isfinite(value), f"{where} must be finite")
    within = lo <= value <= hi if hi_inclusive else lo <= value < hi
    _require(within, f"{where} {value} outside {lo}..{hi}")
    return column, value


def parse(payload: bytes) -> SampleMessage:
    """Turn a raw v2 payload into a message, or say why it cannot be one."""
    try:
        data = json.loads(payload)
    except (ValueError, UnicodeDecodeError) as exc:
        raise InvalidMessage(f"not JSON: {exc}") from exc

    _require(isinstance(data, dict), "payload is not a JSON object")
    _require(data.get("v") == CONTRACT_VERSION,
             f"unsupported contract version {data.get('v')!r}")

    device_id = data.get("dev")
    _require(isinstance(device_id, str) and 0 < len(device_id) <= 64, "dev must be a short string")
    firmware = data.get("fw")
    _require(isinstance(firmware, str) and 0 < len(firmware) <= 32, "fw must be a short string")

    device_time = parse_device_clock(data)

    # A closed schema, top to bottom. An unrecognised key is rejected rather than
    # ignored: a misspelt group would otherwise record a month of nothing under a
    # name no query will ever ask for.
    unknown = set(data) - ENVELOPE - set(SCHEMA)
    _require(not unknown, f"unknown field(s): {', '.join(sorted(unknown))}")

    values: dict[str, float] = {}
    for group, spec in SCHEMA.items():
        if group not in data:
            continue
        body = data[group]
        _require(isinstance(body, dict), f"{group} must be an object")
        extra = set(body) - set(spec)
        _require(not extra, f"unknown field(s) in {group}: {', '.join(sorted(extra))}")
        missing = set(spec) - set(body)
        _require(not missing, f"{group} is missing {', '.join(sorted(missing))}")
        for name, raw in body.items():
            column, value = _measurement(group, name, raw)
            values[column] = value

    measurements = Measurements(**values)
    # A window with nothing in it is not a sample. Storing it would make the row
    # count claim coverage the node never provided.
    _require(measurements.present() > 0, "no measurement groups")

    return SampleMessage(
        version=CONTRACT_VERSION,
        device_id=device_id,
        firmware=firmware,
        boot_id=_int(data, "boot", 0, 0xFFFFFFFF),
        seq=_int(data, "seq", 0, MAX_SAFE_INT),
        device_time=device_time,
        uptime_ms=_int(data, "up", 0, MAX_SAFE_INT),
        window_ms=_int(data, "win", 100, 60000),
        quality=_int(data, "q", 0, 0xFFFF),
        measurements=measurements,
    )


def resolve(msg: SampleMessage, received_at: datetime, anchors: BootAnchors) -> ResolvedSample:
    instant, quality = resolve_instant(msg, received_at, anchors)
    return ResolvedSample(
        time=instant,
        device_id=msg.device_id,
        boot_id=msg.boot_id,
        seq=msg.seq,
        device_time=msg.device_time,
        received_at=received_at,
        uptime_ms=msg.uptime_ms,
        window_ms=msg.window_ms,
        quality=quality,
        firmware=msg.firmware,
        measurements=msg.measurements,
    )
