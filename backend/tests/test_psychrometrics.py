"""Checked against published reference values, not against the code's own output.

A test that only asserts what the function currently returns locks in whatever
was written, including the mistake. These figures come from standard
psychrometric tables and worked examples, with tolerances wide enough for the
difference between fitted equations and narrow enough to catch a real error.
"""

import math

import pytest

from psychron.domain.psychrometrics import (
    absolute_humidity_g_m3,
    condensation_margin_c,
    derive,
    dew_point_c,
    heat_index_c,
    saturation_vapour_pressure_kpa,
    vapour_pressure_deficit_kpa,
)


class TestDewPoint:
    @pytest.mark.parametrize("t,rh,expected", [
        (20.0, 50.0, 9.3),     # the textbook case
        (25.0, 60.0, 16.7),
        (30.0, 80.0, 26.2),
        (10.0, 40.0, -3.0),    # below freezing dew point in cool dry air
        (0.0, 60.0, -6.7),
    ])
    def test_matches_reference_tables(self, t, rh, expected):
        assert dew_point_c(t, rh) == pytest.approx(expected, abs=0.25)

    def test_saturated_air_dews_at_its_own_temperature(self):
        for t in (-5.0, 0.0, 15.0, 30.0):
            assert dew_point_c(t, 100.0) == pytest.approx(t, abs=0.05)

    def test_dew_point_never_exceeds_temperature(self):
        for t in range(-20, 50, 5):
            for rh in (1, 10, 50, 90, 100):
                assert dew_point_c(float(t), float(rh)) <= t + 1e-6

    @pytest.mark.parametrize("t,rh", [(10.0, 40.0), (20.0, 50.0), (30.0, 80.0), (5.0, 95.0)])
    def test_agrees_with_the_vapour_pressure_route(self, t, rh):
        """Cross-check by a second, independent formulation.

        A reference value copied from a table can be copied wrong — one of these
        was, and this is the check that would have caught it without needing the
        table at all. Saturated pressure at the dew point must equal the actual
        vapour pressure of the air, by definition.
        """
        from psychron.domain.psychrometrics import (
            saturation_vapour_pressure_kpa as svp, vapour_pressure_kpa as vp)
        td = dew_point_c(t, rh)
        assert svp(td) == pytest.approx(vp(t, rh), rel=0.01)

    def test_undefined_at_zero_humidity(self):
        with pytest.raises(ValueError):
            dew_point_c(20.0, 0.0)


class TestAbsoluteHumidity:
    @pytest.mark.parametrize("t,rh,expected", [
        (20.0, 50.0, 8.65),
        (25.0, 60.0, 13.8),
        (30.0, 100.0, 30.4),
        (0.0, 50.0, 2.4),
    ])
    def test_matches_reference_tables(self, t, rh, expected):
        assert absolute_humidity_g_m3(t, rh) == pytest.approx(expected, rel=0.03)

    def test_warming_air_without_adding_water_leaves_it_unchanged(self):
        # The property that makes this worth reporting at all: relative humidity
        # falls as air warms, absolute humidity does not.
        t1, rh1 = 18.0, 60.0
        ah = absolute_humidity_g_m3(t1, rh1)

        # Same water, warmed to 26 C: relative humidity must fall to hold it.
        from psychron.domain.psychrometrics import saturation_vapour_pressure_kpa as svp
        rh2 = rh1 * svp(t1) / svp(26.0)
        assert rh2 < rh1
        assert absolute_humidity_g_m3(26.0, rh2) == pytest.approx(ah, rel=0.03)


class TestVapourPressure:
    @pytest.mark.parametrize("t,expected_kpa", [
        (0.0, 0.611), (10.0, 1.228), (20.0, 2.338), (30.0, 4.243), (40.0, 7.376),
    ])
    def test_saturation_pressure_matches_tables(self, t, expected_kpa):
        assert saturation_vapour_pressure_kpa(t) == pytest.approx(expected_kpa, rel=0.02)

    def test_deficit_is_zero_in_saturated_air(self):
        assert vapour_pressure_deficit_kpa(22.0, 100.0) == pytest.approx(0.0, abs=1e-9)

    def test_deficit_grows_with_temperature_at_fixed_humidity(self):
        # The reason humidity alone is a poor comfort proxy: 50% at 30 C pulls
        # far harder than 50% at 15 C.
        cool = vapour_pressure_deficit_kpa(15.0, 50.0)
        warm = vapour_pressure_deficit_kpa(30.0, 50.0)
        assert warm > cool * 2


class TestHeatIndex:
    def test_returns_dry_bulb_when_too_cool_for_the_regression(self):
        for t in (10.0, 20.0, 25.0):
            assert heat_index_c(t, 70.0) == t

    @pytest.mark.parametrize("t,rh,expected", [
        (30.0, 70.0, 35.0),
        (35.0, 60.0, 45.0),
        (32.0, 90.0, 48.0),
    ])
    def test_matches_the_published_table(self, t, rh, expected):
        assert heat_index_c(t, rh) == pytest.approx(expected, abs=1.5)

    def test_feels_hotter_as_humidity_rises(self):
        assert heat_index_c(32.0, 80.0) > heat_index_c(32.0, 40.0)


class TestCondensationMargin:
    def test_is_zero_in_saturated_air(self):
        assert condensation_margin_c(18.0, 100.0) == pytest.approx(0.0, abs=0.05)

    def test_typical_indoor_air_has_a_wide_margin(self):
        assert condensation_margin_c(21.0, 45.0) > 8.0


class TestDerive:
    def test_returns_every_quantity_for_a_good_reading(self):
        d = derive(23.4, 51.2)
        assert set(d) == {"dew_point_c", "absolute_humidity_g_m3",
                          "vapour_pressure_deficit_kpa", "heat_index_c",
                          "condensation_margin_c"}
        assert all(v is not None and math.isfinite(v) for v in d.values())

    @pytest.mark.parametrize("t,rh", [(None, 50.0), (23.0, None), (None, None), (23.0, 0.0)])
    def test_nulls_in_produce_nulls_out_rather_than_an_exception(self, t, rh):
        # A failed sensor read must still yield a reading the API can report,
        # with the derived values visibly absent for the same reason.
        assert all(v is None for v in derive(t, rh).values())
