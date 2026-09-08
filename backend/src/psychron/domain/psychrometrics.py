"""Quantities derived from temperature and relative humidity.

The sensor measures two things. Everything here is computed from those two, and
the distinction matters enough that the API keeps them in separate objects: a
measured value carries sensor error, a derived one carries sensor error plus the
error of a fitted equation, and treating them alike hides that.

Pure functions of their arguments, so they can be checked against published
reference values without a database or a device.
"""

from __future__ import annotations

import math

# Magnus-Tetens coefficients over water, valid roughly -45..60 C. Sonntag 1990.
_MAGNUS_B = 17.62
_MAGNUS_C = 243.12


def saturation_vapour_pressure_kpa(temperature_c: float) -> float:
    """Pressure of water vapour in air that is saturated at this temperature."""
    return 0.61078 * math.exp(17.27 * temperature_c / (temperature_c + 237.3))


def vapour_pressure_kpa(temperature_c: float, humidity_pct: float) -> float:
    return saturation_vapour_pressure_kpa(temperature_c) * humidity_pct / 100.0


def dew_point_c(temperature_c: float, humidity_pct: float) -> float:
    """Temperature at which this air would begin to condense.

    The single most useful derived number here: the gap between it and the
    surface temperature is how close a wall, a window or a lens is to getting
    wet, which relative humidity alone never tells you.
    """
    if humidity_pct <= 0:
        raise ValueError("dew point is undefined at zero humidity")
    gamma = (math.log(humidity_pct / 100.0)
             + (_MAGNUS_B * temperature_c) / (_MAGNUS_C + temperature_c))
    return _MAGNUS_C * gamma / (_MAGNUS_B - gamma)


def absolute_humidity_g_m3(temperature_c: float, humidity_pct: float) -> float:
    """Grams of water per cubic metre of air.

    Unlike relative humidity this does not move when the temperature does, so it
    is what shows whether moisture actually entered or left the room rather than
    the air merely warming up around the same water.
    """
    vp = vapour_pressure_kpa(temperature_c, humidity_pct) * 1000.0  # to pascals
    return vp / (461.5 * (temperature_c + 273.15)) * 1000.0        # kg -> g


def vapour_pressure_deficit_kpa(temperature_c: float, humidity_pct: float) -> float:
    """How hard the air pulls water out of anything wet.

    The number growers actually control for, and a better comfort proxy indoors
    than humidity: the same 50% means very different things at 15 C and at 30 C.
    """
    return saturation_vapour_pressure_kpa(temperature_c) * (1.0 - humidity_pct / 100.0)


def heat_index_c(temperature_c: float, humidity_pct: float) -> float:
    """Rothfusz apparent temperature, in Celsius.

    Only defined in warm air; below about 27 C the regression is meaningless and
    the dry-bulb temperature is returned unchanged, which is what the equation's
    own authors specify rather than a shortcut.
    """
    t_f = temperature_c * 9.0 / 5.0 + 32.0
    if t_f < 80.0:
        return temperature_c

    r = humidity_pct
    hi = (-42.379 + 2.04901523 * t_f + 10.14333127 * r
          - 0.22475541 * t_f * r - 0.00683783 * t_f * t_f
          - 0.05481717 * r * r + 0.00122874 * t_f * t_f * r
          + 0.00085282 * t_f * r * r - 0.00000199 * t_f * t_f * r * r)

    if r < 13.0 and 80.0 <= t_f <= 112.0:
        hi -= ((13.0 - r) / 4.0) * math.sqrt((17.0 - abs(t_f - 95.0)) / 17.0)
    elif r > 85.0 and 80.0 <= t_f <= 87.0:
        hi += ((r - 85.0) / 10.0) * ((87.0 - t_f) / 5.0)

    return (hi - 32.0) * 5.0 / 9.0


def condensation_margin_c(temperature_c: float, humidity_pct: float) -> float:
    """Degrees of cooling this air can take before it starts condensing."""
    return temperature_c - dew_point_c(temperature_c, humidity_pct)


def derive(temperature_c: float | None, humidity_pct: float | None) -> dict[str, float | None]:
    """Everything derivable from one reading, or nulls when it cannot be.

    A failed sensor read produces nulls rather than an exception: the API still
    has a reading to report, and the caller should see that the derived values
    are absent for the same reason the measured ones are.
    """
    if temperature_c is None or humidity_pct is None or humidity_pct <= 0:
        return {k: None for k in
                ("dew_point_c", "absolute_humidity_g_m3", "vapour_pressure_deficit_kpa",
                 "heat_index_c", "condensation_margin_c")}
    return {
        "dew_point_c": dew_point_c(temperature_c, humidity_pct),
        "absolute_humidity_g_m3": absolute_humidity_g_m3(temperature_c, humidity_pct),
        "vapour_pressure_deficit_kpa": vapour_pressure_deficit_kpa(temperature_c, humidity_pct),
        "heat_index_c": heat_index_c(temperature_c, humidity_pct),
        "condensation_margin_c": condensation_margin_c(temperature_c, humidity_pct),
    }
