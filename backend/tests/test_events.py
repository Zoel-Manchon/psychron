import json
from datetime import datetime, timedelta, timezone

import pytest

from psychron.domain import events, telemetry
from psychron.domain.events import parse, resolve
from psychron.domain.telemetry import BootAnchors, InvalidMessage, Q_TIME_FROM_ANCHOR

T0 = datetime(2026, 9, 15, 3, 14, 15, tzinfo=timezone.utc)


def payload(**over) -> bytes:
    base = {"v": 2, "dev": "phone-01", "fw": "android-0.5.0", "boot": 2718281828, "seq": 7,
            "ts": int(T0.timestamp()), "ms": 250, "up": 3_600_000, "q": 0,
            "kind": "vibration", "dur": 1840, "pga": 0.412, "ratio": 6.3, "freq": 11.5}
    base.update(over)
    return json.dumps({k: v for k, v in base.items() if v is not ...}).encode()


class TestParse:
    def test_a_vibration_event_parses(self):
        e = parse(payload())
        assert (e.kind, e.duration_ms, e.pga_ms2, e.sta_lta, e.freq_hz) == ("vibration", 1840, 0.412, 6.3, 11.5)
        assert e.device_time == T0 + timedelta(milliseconds=250)

    def test_the_frequency_is_optional(self):
        assert parse(payload(freq=...)).freq_hz is None

    def test_an_unknown_kind_is_rejected(self):
        with pytest.raises(InvalidMessage, match="kind must be one of"):
            parse(payload(kind="earthquake"))

    def test_fields_of_another_kind_or_none_are_rejected(self):
        with pytest.raises(InvalidMessage, match="unknown field"):
            parse(payload(magnitude=4.5))

    def test_a_sample_is_not_an_event(self):
        # The window length belongs to samples; an event arriving with one is a
        # sample sent to the wrong topic, not an event with a spare field.
        with pytest.raises(InvalidMessage, match="unknown field"):
            parse(payload(win=2000))

    @pytest.mark.parametrize(("field", "value"), [("dur", 0), ("pga", 161.0), ("ratio", 0.5), ("freq", 101.0)])
    def test_bounds_are_enforced(self, field, value):
        with pytest.raises(InvalidMessage, match="outside"):
            parse(payload(**{field: value}))

    def test_a_missing_required_field_is_rejected(self):
        with pytest.raises(InvalidMessage, match="missing pga"):
            parse(payload(pga=...))


class TestTimestamps:
    def test_events_share_the_timestamp_rule(self):
        assert events.resolve_instant is telemetry.resolve_instant

    def test_a_late_event_is_placed_by_the_boot_anchor_its_samples_set(self):
        anchors = BootAnchors()
        resolve(parse(payload()), T0, anchors)
        # Five minutes of uptime later, delivered an hour late with no clock.
        late = parse(payload(seq=8, ts=None, ms=..., up=3_600_000 + 300_000))
        r = resolve(late, T0 + timedelta(hours=1), anchors)
        assert r.time == T0 + timedelta(milliseconds=250) + timedelta(minutes=5)
        assert r.quality & Q_TIME_FROM_ANCHOR
