import json
from datetime import datetime, timedelta, timezone

import pytest

from psychron.domain import samples, telemetry
from psychron.domain.samples import SCHEMA, parse, resolve
from psychron.domain.telemetry import (
    Q_REPLAYED,
    Q_TIME_FROM_ANCHOR,
    Q_TIME_FROM_ARRIVAL,
    BootAnchors,
    InvalidMessage,
)

T0 = datetime(2026, 9, 14, 12, 0, 0, tzinfo=timezone.utc)

FULL = {
    "baro": {"hpa": 1013.42},
    "light": {"lux": 312.0},
    "sound": {"rms_dbfs": -48.2, "peak_dbfs": -31.0},
    "accel": {"rms": 0.042, "peak": 0.31},
    "gyro": {"rms": 0.011, "peak": 0.09},
    "mag": {"ut": 41.2, "heading": 212.5},
    "batt": {"c": 31.4},
}


# Revision 2: the groups a phone adds once it has location, a modem and a
# weighted sound meter. Every field filled, optional ones included.
REVISION_2 = {
    "loc": {"lat": 40.416775, "lon": -3.70379, "acc": 4.5, "alt": 657.2, "alt_acc": 3.1, "spd": 1.2},
    "noise": {"laeq": -52.4, "lamax": -41.0, "l10": -47.9, "l90": -58.3},
    "cell": {"rat": "nr", "rsrp": -97.0, "rsrq": -11.0, "sinr": 8.5, "band": 78},
    "net": {"via": "cell", "vpn": True, "rtt": 84.0},
}


def payload(groups=None, **over) -> bytes:
    base = {"v": 2, "dev": "phone-01", "fw": "0.1.0", "boot": 3141592653, "seq": 42,
            "ts": int(T0.timestamp()), "up": 84000, "win": 2000, "q": 0}
    base.update(FULL if groups is None else groups)
    base.update(over)
    return json.dumps(base).encode()


def without(group: str) -> dict:
    return {k: v for k, v in FULL.items() if k != group}


class TestParse:
    def test_accepts_every_group(self):
        msg = parse(payload())
        m = msg.measurements
        assert msg.device_id == "phone-01"
        assert msg.window_ms == 2000
        assert m.pressure_hpa == pytest.approx(1013.42)
        assert m.heading_deg == pytest.approx(212.5)
        assert m.battery_temp_c == pytest.approx(31.4)
        assert m.present() == 11

    def test_the_contract_example_parses_as_written(self):
        # The document and the code must not disagree about the one example a
        # reader will copy from.
        # Explicit encoding: without it Windows reads the file as cp1252 and the
        # superscripts in the document stop the test before it tests anything.
        doc = (__import__("pathlib").Path(__file__).parents[2] / "docs"
               / "CONTRACT-v2.md").read_text(encoding="utf-8")
        block = doc.split("```json", 1)[1].split("```", 1)[0]
        assert parse(block.encode()).measurements.present() == 29

    def test_an_absent_group_means_no_sample(self):
        m = parse(payload(without("baro"))).measurements
        assert m.pressure_hpa is None
        assert m.illuminance_lux == pytest.approx(312.0)

    def test_a_single_group_is_enough(self):
        assert parse(payload({"batt": {"c": 30.0}})).measurements.present() == 1

    def test_a_window_with_nothing_in_it_is_rejected(self):
        with pytest.raises(InvalidMessage, match="no measurement groups"):
            parse(payload({}))

    def test_uptime_is_not_limited_to_32_bits(self):
        # A phone stays up for months; v1's 49.7-day wrap was an ESP32 limit.
        fifty_days = 50 * 24 * 3600 * 1000
        assert parse(payload(up=fifty_days)).uptime_ms == fifty_days

    def test_uptime_beyond_exact_json_integers_is_rejected(self):
        with pytest.raises(InvalidMessage, match="up out of range"):
            parse(payload(up=2**53))

    @pytest.mark.parametrize("win", [99, 60001])
    def test_window_length_is_bounded(self, win):
        with pytest.raises(InvalidMessage, match="win out of range"):
            parse(payload(win=win))


class TestClosedSchema:
    def test_null_inside_a_present_group_is_rejected(self):
        with pytest.raises(InvalidMessage, match="omit the group"):
            parse(payload({"baro": {"hpa": None}}))

    def test_an_unknown_group_is_rejected(self):
        with pytest.raises(InvalidMessage, match="unknown field"):
            parse(payload(humidity={"pct": 50}))

    def test_a_misspelt_field_is_rejected(self):
        with pytest.raises(InvalidMessage, match="unknown field"):
            parse(payload({"light": {"luxx": 300}}))

    def test_a_group_missing_a_field_is_rejected(self):
        with pytest.raises(InvalidMessage, match="missing"):
            parse(payload({"sound": {"rms_dbfs": -40.0}}))

    def test_a_group_that_is_not_an_object_is_rejected(self):
        with pytest.raises(InvalidMessage, match="must be an object"):
            parse(payload({"baro": 1013}))

    @pytest.mark.parametrize("bad", [float("nan"), float("inf")])
    def test_non_finite_values_are_rejected(self, bad):
        # json.dumps writes NaN and Infinity, and json.loads reads them back.
        with pytest.raises(InvalidMessage, match="finite"):
            parse(payload({"baro": {"hpa": bad}}))

    def test_booleans_are_not_numbers(self):
        with pytest.raises(InvalidMessage, match="must be a number"):
            parse(payload({"batt": {"c": True}}))


class TestRanges:
    @pytest.mark.parametrize(
        ("group", "field"),
        [(g, f) for g, spec in SCHEMA.items() for f, s in spec.items()
         if s.kind in ("number", "integer")],
    )
    def test_every_bound_is_enforced(self, group, field):
        spec = SCHEMA[group][field]
        body = dict({**FULL, **REVISION_2}[group])
        for value in (spec.lo - 1, spec.hi + 1):
            body[field] = int(value) if spec.kind == "integer" else value
            with pytest.raises(InvalidMessage, match="outside"):
                parse(payload({group: body}))

    def test_heading_of_360_is_rejected_because_it_is_0(self):
        with pytest.raises(InvalidMessage, match="outside"):
            parse(payload({"mag": {"ut": 40.0, "heading": 360.0}}))
        assert parse(payload({"mag": {"ut": 40.0, "heading": 0.0}})).measurements.heading_deg == 0.0

    def test_sound_at_full_scale_is_accepted(self):
        m = parse(payload({"sound": {"rms_dbfs": 0.0, "peak_dbfs": 0.0}})).measurements
        assert m.sound_peak_dbfs == 0.0


class TestRevision2:
    def test_every_revision_group_parses(self):
        m = parse(payload({**FULL, **REVISION_2})).measurements
        assert m.present() == 29
        assert m.lat == pytest.approx(40.416775)
        assert m.alt_msl_m == pytest.approx(657.2)
        assert m.noise_l90_dbfs == pytest.approx(-58.3)
        assert m.cell_rat == "nr" and m.cell_band == 78
        assert m.net_via == "cell" and m.net_vpn is True

    def test_optional_fields_may_be_absent(self):
        m = parse(payload({"loc": {"lat": 1.0, "lon": 2.0, "acc": 30.0},
                           "noise": {"laeq": -60.0, "lamax": -50.0},
                           "cell": {"rat": "lte", "rsrp": -110.0, "rsrq": -14.0},
                           "net": {"via": "wifi", "vpn": False}})).measurements
        assert m.alt_msl_m is None and m.speed_ms is None
        assert m.noise_l10_dbfs is None
        assert m.cell_sinr_db is None and m.cell_band is None
        assert m.net_rtt_ms is None and m.net_vpn is False

    def test_required_fields_are_still_required(self):
        with pytest.raises(InvalidMessage, match="missing lon"):
            parse(payload({"loc": {"lat": 1.0, "acc": 5.0}}))

    def test_an_optional_field_is_not_nullable(self):
        # Absent means unmeasured; null would be the second way of saying it.
        with pytest.raises(InvalidMessage, match="omit"):
            parse(payload({"cell": {"rat": "lte", "rsrp": -100.0, "rsrq": -10.0, "sinr": None}}))

    def test_altitude_accuracy_needs_an_altitude(self):
        with pytest.raises(InvalidMessage, match="alt_acc without"):
            parse(payload({"loc": {"lat": 1.0, "lon": 2.0, "acc": 5.0, "alt_acc": 3.0}}))

    @pytest.mark.parametrize("rat", ["LTE", "5g", "umts", 4])
    def test_radio_technology_is_a_closed_set(self, rat):
        with pytest.raises(InvalidMessage, match="must be one of"):
            parse(payload({"cell": {"rat": rat, "rsrp": -100.0, "rsrq": -10.0}}))

    def test_a_flag_is_a_json_boolean_not_a_number(self):
        with pytest.raises(InvalidMessage, match="true or false"):
            parse(payload({"net": {"via": "wifi", "vpn": 1}}))

    def test_a_band_is_an_integer(self):
        with pytest.raises(InvalidMessage, match="must be an integer"):
            parse(payload({"cell": {"rat": "nr", "rsrp": -90.0, "rsrq": -9.0, "band": 78.5}}))


class TestSharedTimestampRule:
    def test_v2_uses_the_same_function_as_v1(self):
        # Not a behavioural test: a guard against someone re-implementing the rule
        # in this module, which is exactly how two versions start to disagree.
        assert samples.resolve_instant is telemetry.resolve_instant

    def test_a_synced_clock_is_believed(self):
        s = resolve(parse(payload()), T0 + timedelta(seconds=1), BootAnchors())
        assert s.time == T0
        assert s.quality & (Q_TIME_FROM_ANCHOR | Q_TIME_FROM_ARRIVAL) == 0

    def test_a_replayed_window_is_placed_by_its_boot_anchor(self):
        anchors = BootAnchors()
        live = parse(payload(up=84000))
        resolve(live, T0, anchors)

        # Taken ten minutes later by the device's own uptime, delivered an hour
        # after that: arrival time is useless, the anchor is not.
        ten_min = 10 * 60 * 1000
        late = parse(payload(seq=43, up=84000 + ten_min, ts=None, q=Q_REPLAYED))
        s = resolve(late, T0 + timedelta(hours=1, minutes=10), anchors)
        assert s.time == T0 + timedelta(minutes=10)
        assert s.quality & Q_TIME_FROM_ANCHOR

    def test_anchors_do_not_leak_between_devices(self):
        anchors = BootAnchors()
        resolve(parse(payload()), T0, anchors)
        other = parse(payload(dev="phone-02", ts=None))
        arrival = T0 + timedelta(minutes=30)
        assert resolve(other, arrival, anchors).time == arrival
