"""The sub-second device clock, `ms`, in both contract versions."""

import json
from datetime import datetime, timedelta, timezone

import pytest

from psychron.domain import samples, telemetry
from psychron.domain.telemetry import BootAnchors, InvalidMessage, parse_device_clock

TS = 1789406501
T = datetime.fromtimestamp(TS, tz=timezone.utc)


def v1(**over) -> bytes:
    base = {"v": 1, "dev": "esp32-01", "fw": "1.1.0", "boot": 7, "seq": 1,
            "ts": TS, "up": 1000, "t": 23.4, "h": 55.0, "q": 0}
    base.update(over)
    return json.dumps(base).encode()


def v2(**over) -> bytes:
    base = {"v": 2, "dev": "phone-01", "fw": "android-0.2.0", "boot": 9, "seq": 1,
            "ts": TS, "up": 2000, "win": 2000, "q": 0, "batt": {"c": 30.0}}
    base.update(over)
    return json.dumps(base).encode()


@pytest.mark.parametrize("encode, parse", [(v1, telemetry.parse), (v2, samples.parse)])
class TestBothContracts:
    def test_milliseconds_are_added_to_the_second(self, encode, parse):
        assert parse(encode(ms=437)).device_time == T + timedelta(milliseconds=437)

    def test_a_message_without_ms_is_still_valid(self, encode, parse):
        # Every message sent before the field existed, and any device not yet
        # updated: known to the second, and still correct to the second.
        assert parse(encode()).device_time == T

    def test_ms_without_a_clock_is_rejected(self, encode, parse):
        with pytest.raises(InvalidMessage, match="ms without ts"):
            parse(encode(ts=None, ms=120))

    def test_an_unknown_clock_with_no_ms_is_fine(self, encode, parse):
        assert parse(encode(ts=None)).device_time is None

    @pytest.mark.parametrize("bad", [-1, 1000, 5000])
    def test_ms_must_be_within_the_second(self, encode, parse, bad):
        with pytest.raises(InvalidMessage, match="ms out of range"):
            parse(encode(ms=bad))

    @pytest.mark.parametrize("bad", [1.5, "437", True])
    def test_ms_must_be_an_integer(self, encode, parse, bad):
        with pytest.raises(InvalidMessage, match="ms must be an integer"):
            parse(encode(ms=bad))


def test_the_extremes_of_the_second_are_exact():
    assert parse_device_clock({"ts": TS, "ms": 0}) == T
    assert parse_device_clock({"ts": TS, "ms": 999}) == T + timedelta(milliseconds=999)
    # Exact to the microsecond: the arithmetic is integer, never a float epoch.
    assert parse_device_clock({"ts": TS, "ms": 1}).microsecond == 1000


def test_two_nodes_a_quarter_second_apart_are_no_longer_the_same_instant():
    # The reason for the field. With seconds alone both of these read as TS, and
    # nothing could say which came first or whether they coincided.
    esp = telemetry.parse(v1(ms=100)).device_time
    phone = samples.parse(v2(ms=350)).device_time
    assert phone - esp == timedelta(milliseconds=250)


def test_the_resolution_rule_keeps_the_millisecond():
    msg = samples.parse(v2(ms=437))
    instant, quality = telemetry.resolve_instant(msg, T + timedelta(milliseconds=460), BootAnchors())
    assert instant == T + timedelta(milliseconds=437)
    assert quality == 0
