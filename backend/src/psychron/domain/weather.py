"""Pressure turned into things a person reads: sea level, height, an outlook.

Pure functions over hPa and metres. The barometer measures station pressure, which
depends on how high the phone is as much as on the weather; everything here exists
to separate the two.
"""

from __future__ import annotations

from dataclasses import dataclass

# ICAO standard atmosphere at sea level.
T0_K = 288.15
LAPSE_K_PER_M = 0.0065
# g·M / (R·L): the exponent of the barometric formula in a constant-lapse layer.
BAROMETRIC_EXPONENT = 5.25588


def sea_level_pressure(station_hpa: float, altitude_m: float) -> float:
    """Station pressure reduced to mean sea level through the standard atmosphere.

    This is QNH, the reduction aviation uses, rather than the synoptic QFF that
    corrects for the actual air temperature: the phone's only thermometer is its
    battery, and correcting with that would add the charger's heat to the weather.
    Within a few hundred metres of sea level the two agree to about a hectopascal.
    """
    return station_hpa * (1.0 - LAPSE_K_PER_M * altitude_m / T0_K) ** -BAROMETRIC_EXPONENT


def station_pressure(sea_level_hpa: float, altitude_m: float) -> float:
    """The inverse of `sea_level_pressure`: what a barometer at that altitude reads."""
    return sea_level_hpa * (1.0 - LAPSE_K_PER_M * altitude_m / T0_K) ** BAROMETRIC_EXPONENT


def height_change(hpa: float, reference_hpa: float) -> float:
    """Metres climbed between two pressures, positive going up.

    Good for relative height over minutes — a flight of stairs is about 0.35 hPa —
    and useless over hours, when the weather moves the pressure by more than any
    building does.
    """
    return (T0_K / LAPSE_K_PER_M) * (1.0 - (hpa / reference_hpa) ** (1.0 / BAROMETRIC_EXPONENT))


# A three-hour change beyond this is a trend; inside it, noise and tides.
TREND_HPA = 1.6

_FALLING = {
    1: "Settled fine", 2: "Fine weather", 3: "Fine, becoming less settled",
    4: "Fairly fine, showery later", 5: "Showery, becoming more unsettled",
    6: "Unsettled, rain later", 7: "Rain at times, worse later",
    8: "Rain at times, becoming very unsettled", 9: "Very unsettled, rain",
}
_STEADY = {
    10: "Settled fine", 11: "Fine weather", 12: "Fine, possibly showers",
    13: "Fairly fine, showers likely", 14: "Showery, bright intervals",
    15: "Changeable, some rain", 16: "Unsettled, rain at times",
    17: "Rain at frequent intervals", 18: "Very unsettled, rain", 19: "Stormy, much rain",
}
_RISING = {
    20: "Settled fine", 21: "Fine weather", 22: "Becoming fine", 23: "Fairly fine, improving",
    24: "Fairly fine, possibly showers early", 25: "Showery early, improving",
    26: "Changeable, mending", 27: "Rather unsettled, clearing later",
    28: "Unsettled, probably improving", 29: "Unsettled, short fine intervals",
    30: "Very unsettled, finer at times", 31: "Stormy, possibly improving", 32: "Stormy, much rain",
}


@dataclass(frozen=True)
class Outlook:
    number: int          # Zambretti's forecast number, 1–32
    trend: str           # falling | steady | rising
    text: str


def zambretti(sea_level_hpa: float, change_3h_hpa: float) -> Outlook:
    """The Negretti & Zambra forecaster, in its common linear form.

    A 1915 pocket instrument that turns sea-level pressure and its three-hour trend
    into one of 32 short outlooks for the next twelve hours or so. No wind, no
    season, no location: it is right often enough to be interesting and wrong
    often enough that the panel labels it for what it is.
    """
    if change_3h_hpa <= -TREND_HPA:
        trend, z, lo, hi, table = "falling", 127 - 0.12 * sea_level_hpa, 1, 9, _FALLING
    elif change_3h_hpa >= TREND_HPA:
        trend, z, lo, hi, table = "rising", 185 - 0.16 * sea_level_hpa, 20, 32, _RISING
    else:
        trend, z, lo, hi, table = "steady", 144 - 0.13 * sea_level_hpa, 10, 19, _STEADY
    # Clamped rather than rejected: the formulas were fitted to 950–1050 hPa, and
    # beyond that the nearest outlook in the same trend is the honest answer.
    number = min(hi, max(lo, int(z + 0.5)))
    return Outlook(number=number, trend=trend, text=table[number])
