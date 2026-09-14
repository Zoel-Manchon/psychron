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
from datetime import datetime

from .telemetry import BootAnchors, InvalidMessage, parse_device_clock, resolve_instant

CONTRACT_VERSION = 2

# JSON numbers are exact up to 2^53; beyond that a value can change silently on
# the way through any parser that uses doubles, which is most of them.
MAX_SAFE_INT = 2**53 - 1


@dataclass(frozen=True)
class Spec:
    """One field of one group: where it is stored and what it may hold.

    `required` is the revision that let groups carry quantities a device cannot
    always measure. A GNSS fix without altitude is still a fix, and a cell without
    a SINR report is still a cell; without optional fields each would have to be a
    group of its own, or the whole group would be lost with its weakest field.
    An optional field is absent or valid — never null, which stays the one thing
    the contract does not accept inside a group.
    """

    column: str
    lo: float | None = None
    hi: float | None = None
    hi_inclusive: bool = True
    required: bool = True
    kind: str = "number"                 # number | integer | choice | flag
    choices: tuple[str, ...] = ()


# One table is both the validator and the mapping to storage, so a field cannot be
# accepted on the wire and then quietly have nowhere to go.
SCHEMA: dict[str, dict[str, Spec]] = {
    "baro":  {"hpa":       Spec("pressure_hpa",    300.0, 1100.0)},
    "light": {"lux":       Spec("illuminance_lux", 0.0,   200000.0)},
    "sound": {"rms_dbfs":  Spec("sound_rms_dbfs",  -160.0, 0.0),
              "peak_dbfs": Spec("sound_peak_dbfs", -160.0, 0.0)},
    "accel": {"rms":       Spec("accel_rms",       0.0,   160.0),
              "peak":      Spec("accel_peak",      0.0,   160.0)},
    "gyro":  {"rms":       Spec("gyro_rms",        0.0,   40.0),
              "peak":      Spec("gyro_peak",       0.0,   40.0)},
    "mag":   {"ut":        Spec("magnetic_ut",     0.0,   2000.0),
              # A heading of 360 is a heading of 0 written differently; accepting
              # both would give one direction two representations in the data.
              "heading":   Spec("heading_deg",     0.0,   360.0, hi_inclusive=False)},
    "batt":  {"c":         Spec("battery_temp_c",  -40.0, 100.0)},

    # ── revision 2 ──────────────────────────────────────────────────────────
    # Position from the device's own GNSS. Altitude is above mean sea level, not
    # the WGS84 ellipsoid: the two differ by ~50 m over Spain, which is 6 hPa once
    # used to reduce pressure to sea level.
    "loc":   {"lat":       Spec("lat",             -90.0,  90.0),
              "lon":       Spec("lon",             -180.0, 180.0),
              "acc":       Spec("loc_acc_m",       0.0,   10000.0),
              "alt":       Spec("alt_msl_m",       -500.0, 9000.0, required=False),
              "alt_acc":   Spec("alt_acc_m",       0.0,   10000.0, required=False),
              "spd":       Spec("speed_ms",        0.0,   350.0,   required=False)},
    # A-weighted levels, still relative to full scale: weighting makes the number
    # follow the ear, it does not calibrate the microphone. l10 and l90 describe the
    # trailing minute and are absent until a device has heard enough of one.
    "noise": {"laeq":      Spec("noise_laeq_dbfs",  -160.0, 0.0),
              "lamax":     Spec("noise_lamax_dbfs", -160.0, 0.0),
              "l10":       Spec("noise_l10_dbfs",   -160.0, 0.0, required=False),
              "l90":       Spec("noise_l90_dbfs",   -160.0, 0.0, required=False)},
    # The serving cell. Ranges are 3GPP's reporting ranges for LTE and NR together.
    "cell":  {"rat":       Spec("cell_rat", kind="choice", choices=("lte", "nr")),
              "rsrp":      Spec("cell_rsrp_dbm",   -156.0, -31.0),
              "rsrq":      Spec("cell_rsrq_db",    -43.0,  20.0),
              "sinr":      Spec("cell_sinr_db",    -23.0,  40.0, required=False),
              "band":      Spec("cell_band",       1,      1024, required=False, kind="integer")},
    # How this message's predecessors travelled. rtt is the mean time from publish
    # to the broker's acknowledgement over the previous window.
    "net":   {"via":       Spec("net_via", kind="choice", choices=("wifi", "cell", "ethernet", "other")),
              "vpn":       Spec("net_vpn", kind="flag"),
              "rtt":       Spec("net_rtt_ms",      0.0,   60000.0, required=False)},
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
    lat: float | None = None
    lon: float | None = None
    loc_acc_m: float | None = None
    alt_msl_m: float | None = None
    alt_acc_m: float | None = None
    speed_ms: float | None = None
    noise_laeq_dbfs: float | None = None
    noise_lamax_dbfs: float | None = None
    noise_l10_dbfs: float | None = None
    noise_l90_dbfs: float | None = None
    cell_rat: str | None = None
    cell_rsrp_dbm: float | None = None
    cell_rsrq_db: float | None = None
    cell_sinr_db: float | None = None
    cell_band: int | None = None
    net_via: str | None = None
    net_vpn: bool | None = None
    net_rtt_ms: float | None = None

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


def require(cond: bool, reason: str) -> None:
    if not cond:
        raise InvalidMessage(reason)


def envelope_int(data: dict, key: str, lo: int, hi: int) -> int:
    raw = data.get(key)
    require(isinstance(raw, int) and not isinstance(raw, bool), f"{key} must be an integer")
    require(lo <= raw <= hi, f"{key} out of range")  # type: ignore[operator]
    return int(raw)  # type: ignore[arg-type]


def check_value(where: str, spec: Spec, raw: object) -> float | int | str | bool:
    """Validate one field against its spec, or say exactly what is wrong with it.

    Shared with events.py, so a field means the same thing wherever it appears.
    """
    # Inside a present group every field has a value. Null here would be a second
    # way of saying "no data", and two ways of saying one thing is how a query
    # ends up counting only one of them.
    require(raw is not None, f"{where} is null; omit the group instead")

    if spec.kind == "choice":
        require(isinstance(raw, str) and raw in spec.choices,
                f"{where} must be one of {', '.join(spec.choices)}")
        return raw  # type: ignore[return-value]
    if spec.kind == "flag":
        require(isinstance(raw, bool), f"{where} must be true or false")
        return raw  # type: ignore[return-value]

    if spec.kind == "integer":
        require(isinstance(raw, int) and not isinstance(raw, bool), f"{where} must be an integer")
    else:
        require(isinstance(raw, (int, float)) and not isinstance(raw, bool),
                f"{where} must be a number")
    value = float(raw)  # type: ignore[arg-type]
    require(math.isfinite(value), f"{where} must be finite")
    lo, hi = spec.lo, spec.hi
    within = lo <= value <= hi if spec.hi_inclusive else lo <= value < hi  # type: ignore[operator]
    require(within, f"{where} {value} outside {lo}..{hi}")
    return int(raw) if spec.kind == "integer" else value  # type: ignore[arg-type]


def check_group(group: str, spec: dict[str, Spec], body: object) -> dict[str, object]:
    """Validate one group, returning {column: value} for what it carries."""
    require(isinstance(body, dict), f"{group} must be an object")
    extra = set(body) - set(spec)  # type: ignore[arg-type]
    require(not extra, f"unknown field(s) in {group}: {', '.join(sorted(extra))}")
    missing = {name for name, s in spec.items() if s.required} - set(body)  # type: ignore[arg-type]
    require(not missing, f"{group} is missing {', '.join(sorted(missing))}")
    return {spec[name].column: check_value(f"{group}.{name}", spec[name], raw)
            for name, raw in body.items()}  # type: ignore[union-attr]


def parse(payload: bytes) -> SampleMessage:
    """Turn a raw v2 payload into a message, or say why it cannot be one."""
    try:
        data = json.loads(payload)
    except (ValueError, UnicodeDecodeError) as exc:
        raise InvalidMessage(f"not JSON: {exc}") from exc

    require(isinstance(data, dict), "payload is not a JSON object")
    require(data.get("v") == CONTRACT_VERSION,
            f"unsupported contract version {data.get('v')!r}")

    device_id = data.get("dev")
    require(isinstance(device_id, str) and 0 < len(device_id) <= 64, "dev must be a short string")
    firmware = data.get("fw")
    require(isinstance(firmware, str) and 0 < len(firmware) <= 32, "fw must be a short string")

    device_time = parse_device_clock(data)

    # A closed schema, top to bottom. An unrecognised key is rejected rather than
    # ignored: a misspelt group would otherwise record a month of nothing under a
    # name no query will ever ask for.
    unknown = set(data) - ENVELOPE - set(SCHEMA)
    require(not unknown, f"unknown field(s): {', '.join(sorted(unknown))}")

    values: dict[str, object] = {}
    for group, spec in SCHEMA.items():
        if group in data:
            values.update(check_group(group, spec, data[group]))

    # An accuracy with no value to be the accuracy of describes nothing.
    require(not ("alt_acc_m" in values and "alt_msl_m" not in values),
            "loc.alt_acc without loc.alt")

    measurements = Measurements(**values)  # type: ignore[arg-type]
    # A window with nothing in it is not a sample. Storing it would make the row
    # count claim coverage the node never provided.
    require(measurements.present() > 0, "no measurement groups")

    return SampleMessage(
        version=CONTRACT_VERSION,
        device_id=device_id,
        firmware=firmware,
        boot_id=envelope_int(data, "boot", 0, 0xFFFFFFFF),
        seq=envelope_int(data, "seq", 0, MAX_SAFE_INT),
        device_time=device_time,
        uptime_ms=envelope_int(data, "up", 0, MAX_SAFE_INT),
        window_ms=envelope_int(data, "win", 100, 60000),
        quality=envelope_int(data, "q", 0, 0xFFFF),
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
