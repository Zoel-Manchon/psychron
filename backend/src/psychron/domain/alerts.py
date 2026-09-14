"""When the record says something a person should hear about now.

Rules are data: a value to watch, a level that raises the alert and a different
level that clears it. Different on purpose — with one threshold, a battery
hovering at 42.0 °C raises and clears an alert every few seconds, and a
notification that repeats itself stops being read.

Pure: the evaluator is given what was observed and which alerts are open, and
returns the transitions. Where the numbers come from and where the transitions
go are the adapters' business.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Callable

RAISED = "raised"
CLEARED = "cleared"


@dataclass(frozen=True)
class Observation:
    now: datetime
    # Seconds since each device was last heard from; None if it never has been.
    silent_s: dict[str, float | None]
    # The phone's three-hour pressure change, if the record holds both ends of it.
    pressure_change_3h_hpa: float | None
    # The phone's latest battery temperature, if it is recent.
    battery_temp_c: float | None


@dataclass(frozen=True)
class Rule:
    kind: str
    device_id: str
    value: Callable[[Observation], float | None]
    raises: Callable[[float], bool]
    clears: Callable[[float], bool]
    threshold: float
    describe: Callable[[float], str]


@dataclass(frozen=True)
class Transition:
    kind: str
    device_id: str
    state: str
    at: datetime
    value: float
    threshold: float
    message: str


def default_rules(esp32: str = "esp32-01", phone: str = "phone-01") -> list[Rule]:
    return [
        # "Falling quickly" in the Met Office's bands, the same words the panel
        # uses: more than 3.5 hPa in three hours. Cleared only once the fall has
        # eased below 2 hPa, so a trace wobbling around the line stays one alert.
        Rule("pressure_falling", phone,
             value=lambda o: o.pressure_change_3h_hpa,
             raises=lambda v: v < -3.5, clears=lambda v: v > -2.0, threshold=-3.5,
             describe=lambda v: f"Pressure falling quickly: {v:+.1f} hPa in 3 h"),
        # The ESP32 reads every 3 s. Two minutes of silence is forty readings, far
        # past any reconnect; half a minute of normal traffic clears it.
        Rule("device_offline", esp32,
             value=lambda o: o.silent_s.get(esp32),
             raises=lambda v: v > 120, clears=lambda v: v < 30, threshold=120,
             describe=lambda v: f"ESP32 silent for {v / 60:.0f} min"),
        # Android starts throttling and stops charging in the low to mid 40s.
        Rule("battery_hot", phone,
             value=lambda o: o.battery_temp_c,
             raises=lambda v: v >= 42.0, clears=lambda v: v <= 39.0, threshold=42.0,
             describe=lambda v: f"Phone battery at {v:.1f} °C"),
    ]


def evaluate(observation: Observation, open_alerts: set[tuple[str, str]],
             rules: list[Rule]) -> list[Transition]:
    """The alerts that change state now.

    An unknown value changes nothing in either direction. A device never heard
    from is not "offline", and a pressure change that cannot be computed is not
    evidence the storm has passed.
    """
    out: list[Transition] = []
    for rule in rules:
        value = rule.value(observation)
        if value is None:
            continue
        key = (rule.kind, rule.device_id)
        if key not in open_alerts and rule.raises(value):
            out.append(Transition(rule.kind, rule.device_id, RAISED, observation.now,
                                  value, rule.threshold, rule.describe(value)))
        elif key in open_alerts and rule.clears(value):
            out.append(Transition(rule.kind, rule.device_id, CLEARED, observation.now,
                                  value, rule.threshold, rule.describe(value)))
    return out
