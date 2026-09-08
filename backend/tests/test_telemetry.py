from datetime import datetime, timedelta, timezone

import pytest

from psychron.domain.telemetry import (
    CLOCK_TOLERANCE,
    Q_CLOCK_UNSYNCED,
    Q_REPLAYED,
    Q_TIME_FROM_ANCHOR,
    Q_TIME_FROM_ARRIVAL,
    BootAnchors,
    InvalidMessage,
    TelemetryMessage,
    parse,
    resolve,
)

T0 = datetime(2026, 9, 7, 12, 0, 0, tzinfo=timezone.utc)


def payload(**over) -> bytes:
    import json

    base = {
        "v": 1, "dev": "esp32-01", "fw": "1.0.0", "boot": 2748109371, "seq": 1234,
        "ts": int(T0.timestamp()), "up": 2468000, "t": 23.42, "h": 51.2, "q": 0,
    }
    base.update(over)
    return json.dumps(base).encode()


class TestParse:
    def test_accepts_a_well_formed_message(self):
        msg = parse(payload())
        assert msg.device_id == "esp32-01"
        assert msg.boot_id == 2748109371
        assert msg.temperature_c == pytest.approx(23.42)
        assert msg.device_time == T0

    def test_null_clock_is_not_an_error(self):
        assert parse(payload(ts=None)).device_time is None

    def test_null_readings_are_not_an_error(self):
        msg = parse(payload(t=None, h=None))
        assert msg.temperature_c is None and msg.humidity_pct is None

    @pytest.mark.parametrize("bad,reason", [
        (b"not json at all", "not JSON"),
        (b"[1,2,3]", "not a JSON object"),
        (b'{"v":2}', "unsupported contract version"),
    ])
    def test_rejects_malformed_payloads(self, bad, reason):
        with pytest.raises(InvalidMessage, match=reason):
            parse(bad)

    @pytest.mark.parametrize("over", [
        {"dev": ""}, {"dev": 5}, {"fw": None},
        {"boot": "x"}, {"seq": -1}, {"up": None},
        {"q": -1}, {"ts": 0}, {"ts": "yesterday"},
    ])
    def test_rejects_bad_fields(self, over):
        with pytest.raises(InvalidMessage):
            parse(payload(**over))

    def test_rejects_non_finite_readings(self):
        # NaN and Infinity survive json.loads and would poison every average,
        # minimum and maximum they ever reach.
        for literal in (b"NaN", b"Infinity", b"-Infinity"):
            raw = b'{"v":1,"dev":"d","fw":"1","boot":1,"seq":1,"ts":null,"up":1,"t":' \
                  + literal + b',"h":1.0,"q":0}'
            with pytest.raises(InvalidMessage, match="finite"):
                parse(raw)

    def test_a_true_boolean_is_not_an_integer(self):
        # bool subclasses int in Python, so a naive isinstance check would let
        # {"seq": true} through and store it as 1.
        with pytest.raises(InvalidMessage):
            parse(payload(seq=True))


class TestResolve:
    def test_believes_a_clock_that_agrees_with_arrival(self):
        msg = parse(payload())
        r = resolve(msg, received_at=T0 + timedelta(seconds=1), anchors=BootAnchors())
        assert r.time == T0
        assert not r.quality & (Q_TIME_FROM_ANCHOR | Q_TIME_FROM_ARRIVAL)

    def test_falls_back_to_arrival_when_there_is_no_clock_and_no_anchor(self):
        msg = parse(payload(ts=None, q=Q_CLOCK_UNSYNCED))
        arrival = T0 + timedelta(seconds=3)
        r = resolve(msg, received_at=arrival, anchors=BootAnchors())
        assert r.time == arrival
        assert r.quality & Q_TIME_FROM_ARRIVAL

    def test_distrusts_a_clock_that_disagrees_wildly(self):
        # A device whose NTP sync landed on a bad server, or whose RTC reset.
        msg = parse(payload(ts=int((T0 - timedelta(days=400)).timestamp())))
        arrival = T0
        r = resolve(msg, received_at=arrival, anchors=BootAnchors())
        assert r.time == arrival
        assert r.quality & Q_TIME_FROM_ARRIVAL
        # The claim is still recorded, so the disagreement stays auditable.
        assert r.device_time == T0 - timedelta(days=400)

    def test_tolerance_boundary_is_inclusive(self):
        msg = parse(payload())
        r = resolve(msg, received_at=T0 + CLOCK_TOLERANCE, anchors=BootAnchors())
        assert r.time == T0

    def test_anchor_places_a_reading_taken_before_the_clock_synced(self):
        anchors = BootAnchors()

        # Boot with no network: uptime is right, the date is unknown.
        early = parse(payload(ts=None, q=Q_CLOCK_UNSYNCED, up=5_000, seq=1))
        # Later, NTP lands and a message carries a believable clock.
        synced = parse(payload(ts=int(T0.timestamp()), up=65_000, seq=20))
        resolve(synced, received_at=T0, anchors=anchors)

        # The early reading can now be placed exactly: it happened 60 s earlier.
        r = resolve(early, received_at=T0 + timedelta(seconds=1), anchors=anchors)
        assert r.time == T0 - timedelta(seconds=60)
        assert r.quality & Q_TIME_FROM_ANCHOR

    def test_replayed_readings_are_placed_by_uptime_not_arrival(self):
        """The scenario the offline buffer exists for.

        The link dies for two hours. Readings pile up on flash. When it comes
        back they all arrive within seconds of each other — so arrival time would
        stack two hours of measurements onto one instant, inventing a spike and
        erasing the outage. The anchor puts each one back where it belongs.
        """
        anchors = BootAnchors()
        anchors.seed("esp32-01", 2748109371, boot_epoch=T0)

        recovery = T0 + timedelta(hours=2)
        placed = []
        for i, uptime_min in enumerate([10, 40, 70, 100]):
            msg = parse(payload(ts=None, q=Q_CLOCK_UNSYNCED | Q_REPLAYED,
                                up=uptime_min * 60_000, seq=i + 1))
            # All four arrive at essentially the same moment.
            placed.append(resolve(msg, received_at=recovery + timedelta(milliseconds=i * 50),
                                  anchors=anchors))

        assert [r.time for r in placed] == [
            T0 + timedelta(minutes=m) for m in (10, 40, 70, 100)
        ]
        spread = placed[-1].time - placed[0].time
        assert spread == timedelta(minutes=90), "the outage must keep its shape"
        assert all(r.quality & Q_TIME_FROM_ANCHOR for r in placed)

    def test_anchor_is_taken_from_the_first_trusted_message_only(self):
        # A later message with a slightly different clock must not drag the
        # anchor around, or replayed readings would drift as the drain proceeds.
        anchors = BootAnchors()
        first = parse(payload(ts=int(T0.timestamp()), up=1000))
        resolve(first, received_at=T0, anchors=anchors)

        drifted = parse(payload(ts=int((T0 + timedelta(seconds=61)).timestamp()), up=2000))
        resolve(drifted, received_at=T0 + timedelta(seconds=61), anchors=anchors)

        probe = TelemetryMessage(1, "esp32-01", "1.0.0", 2748109371, 99, None,
                                 1000, 20.0, 50.0, Q_CLOCK_UNSYNCED)
        assert resolve(probe, received_at=T0, anchors=anchors).time == T0

    def test_anchors_do_not_leak_between_boots(self):
        anchors = BootAnchors()
        anchors.seed("esp32-01", 111, boot_epoch=T0)
        other_boot = parse(payload(boot=222, ts=None, q=Q_CLOCK_UNSYNCED))
        arrival = T0 + timedelta(hours=5)
        assert resolve(other_boot, received_at=arrival, anchors=anchors).time == arrival

    def test_anchors_do_not_leak_between_devices(self):
        anchors = BootAnchors()
        anchors.seed("esp32-99", 2748109371, boot_epoch=T0)
        mine = parse(payload(ts=None, q=Q_CLOCK_UNSYNCED))
        arrival = T0 + timedelta(hours=5)
        assert resolve(mine, received_at=arrival, anchors=anchors).time == arrival

    def test_firmware_quality_bits_are_preserved(self):
        msg = parse(payload(q=Q_REPLAYED | 0x08))
        r = resolve(msg, received_at=T0, anchors=BootAnchors())
        assert r.quality & Q_REPLAYED and r.quality & 0x08
