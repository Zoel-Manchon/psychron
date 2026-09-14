"""Contract v2 events: something happened, once, at a moment.

A window summary answers "what was it like over these two seconds". An event
answers "when did this start, and how big was it" — a vibration that lasted 1.8 s
has no natural place in a series of two-second rows, and averaging it into one
would hide exactly the peak worth keeping.

Same envelope as a sample, minus `win`, and the same timestamp rule. `ts` and `up`
mark the **start** of the event, where a sample's mark the end of its window.
See ../../../docs/CONTRACT-v2.md.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime

from .samples import CONTRACT_VERSION, MAX_SAFE_INT, Spec, check_group, envelope_int, require
from .telemetry import BootAnchors, InvalidMessage, parse_device_clock, resolve_instant

# kind -> field -> spec. Closed like the sample schema: an unknown kind is a
# rejection, not a row with a label nobody queries.
KINDS: dict[str, dict[str, Spec]] = {
    "vibration": {
        "dur":   Spec("duration_ms", 1, 600000, kind="integer"),
        "pga":   Spec("pga_ms2",     0.0, 160.0),
        "ratio": Spec("sta_lta",     1.0, 1000.0),
        "freq":  Spec("freq_hz",     0.0, 100.0, required=False),
    },
}

ENVELOPE = {"v", "dev", "fw", "boot", "seq", "ts", "ms", "up", "q", "kind"}


@dataclass(frozen=True)
class EventMessage:
    """A parsed event, with the envelope under v1's names so the shared timestamp
    rule and the boot anchors accept it unchanged."""

    device_id: str
    firmware: str
    boot_id: int
    seq: int
    device_time: datetime | None
    uptime_ms: int
    quality: int
    kind: str
    duration_ms: int
    pga_ms2: float
    sta_lta: float
    freq_hz: float | None


@dataclass(frozen=True)
class ResolvedEvent:
    time: datetime
    received_at: datetime
    quality: int
    message: EventMessage


def parse(payload: bytes) -> EventMessage:
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

    kind = data.get("kind")
    require(isinstance(kind, str) and kind in KINDS,
            f"kind must be one of {', '.join(sorted(KINDS))}")
    spec = KINDS[kind]  # type: ignore[index]

    unknown = set(data) - ENVELOPE - set(spec)
    require(not unknown, f"unknown field(s): {', '.join(sorted(unknown))}")
    # The fields sit at the top level rather than in a group: an event is one
    # thing, and nesting it under its own kind would only repeat the kind.
    values = check_group(kind, spec, {k: data[k] for k in spec if k in data})  # type: ignore[arg-type]

    return EventMessage(
        device_id=device_id,  # type: ignore[arg-type]
        firmware=firmware,  # type: ignore[arg-type]
        boot_id=envelope_int(data, "boot", 0, 0xFFFFFFFF),
        seq=envelope_int(data, "seq", 0, MAX_SAFE_INT),
        device_time=parse_device_clock(data),
        uptime_ms=envelope_int(data, "up", 0, MAX_SAFE_INT),
        quality=envelope_int(data, "q", 0, 0xFFFF),
        kind=kind,  # type: ignore[arg-type]
        duration_ms=values["duration_ms"],  # type: ignore[arg-type]
        pga_ms2=values["pga_ms2"],  # type: ignore[arg-type]
        sta_lta=values["sta_lta"],  # type: ignore[arg-type]
        freq_hz=values.get("freq_hz"),  # type: ignore[arg-type]
    )


def resolve(msg: EventMessage, received_at: datetime, anchors: BootAnchors) -> ResolvedEvent:
    instant, quality = resolve_instant(msg, received_at, anchors)
    return ResolvedEvent(time=instant, received_at=received_at, quality=quality, message=msg)
