import pytest

from psychron.domain.weather import height_change, sea_level_pressure, station_pressure, zambretti


class TestSeaLevel:
    def test_sea_level_is_unchanged(self):
        assert sea_level_pressure(1013.25, 0.0) == pytest.approx(1013.25)

    def test_matches_the_standard_atmosphere(self):
        # ISA: 1500 m sits at 845.6 hPa, so that station reading reduces to 1013.25.
        assert sea_level_pressure(845.56, 1500.0) == pytest.approx(1013.25, abs=0.1)

    def test_madrid_reads_like_a_weather_site(self):
        # A station at 657 m reading 940 hPa is an ordinary day at sea level.
        # By hand: 940 * (1 - 0.0065 * 657 / 288.15) ** -5.25588 = 940 * 1.081637.
        assert sea_level_pressure(940.0, 657.0) == pytest.approx(1016.74, abs=0.05)

    def test_below_sea_level_increases_less_than_zero(self):
        assert sea_level_pressure(1020.0, -30.0) < 1020.0

    def test_station_pressure_is_the_exact_inverse(self):
        for alt in (-200.0, 0.0, 657.0, 2400.0):
            assert sea_level_pressure(station_pressure(1009.3, alt), alt) == pytest.approx(1009.3)


class TestHeight:
    def test_the_same_pressure_is_no_climb(self):
        assert height_change(1000.0, 1000.0) == pytest.approx(0.0)

    def test_a_storey_is_a_third_of_a_hectopascal(self):
        climb = height_change(1013.25 - 0.36, 1013.25)
        assert 2.5 < climb < 3.5

    def test_going_down_is_negative(self):
        assert height_change(1001.0, 1000.0) < 0


class TestZambretti:
    @pytest.mark.parametrize(("hpa", "change", "trend", "number"), [
        (1030.0, -2.0, "falling", 3),
        (990.0, -4.0, "falling", 8),
        (1020.0, 0.4, "steady", 11),
        (985.0, -1.0, "steady", 16),
        (1030.0, 2.5, "rising", 20),
        (990.0, 1.7, "rising", 27),
    ])
    def test_the_linear_formulas(self, hpa, change, trend, number):
        o = zambretti(hpa, change)
        assert (o.trend, o.number) == (trend, number)

    def test_trend_threshold_is_one_point_six_either_way(self):
        assert zambretti(1013.0, -1.59).trend == "steady"
        assert zambretti(1013.0, -1.6).trend == "falling"
        assert zambretti(1013.0, 1.6).trend == "rising"

    @pytest.mark.parametrize(("hpa", "change", "number"), [
        (1080.0, -3.0, 1), (900.0, -3.0, 9), (1080.0, 0.0, 10), (900.0, 0.0, 19),
        (1080.0, 3.0, 20), (900.0, 3.0, 32),
    ])
    def test_out_of_range_pressure_is_clamped_within_its_trend(self, hpa, change, number):
        assert zambretti(hpa, change).number == number

    def test_every_number_has_words(self):
        seen = {zambretti(p / 2, c).text for p in range(1800, 2200) for c in (-3.0, 0.0, 3.0)}
        assert "" not in seen and len(seen) > 20
