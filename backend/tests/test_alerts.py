from datetime import datetime, timezone

from psychron.domain.alerts import CLEARED, RAISED, Observation, default_rules, evaluate

NOW = datetime(2026, 9, 15, 4, 0, tzinfo=timezone.utc)
RULES = default_rules()


def obs(silent=None, change=None, battery=None) -> Observation:
    return Observation(now=NOW, silent_s={"esp32-01": silent},
                       pressure_change_3h_hpa=change, battery_temp_c=battery)


def states(transitions):
    return {(t.kind, t.state) for t in transitions}


class TestRaising:
    def test_a_quiet_system_raises_nothing(self):
        assert evaluate(obs(silent=2, change=-0.4, battery=31.0), set(), RULES) == []

    def test_each_rule_raises_past_its_threshold(self):
        got = evaluate(obs(silent=300, change=-4.2, battery=43.5), set(), RULES)
        assert states(got) == {("device_offline", RAISED), ("pressure_falling", RAISED),
                               ("battery_hot", RAISED)}

    def test_the_message_says_what_happened(self):
        [t] = evaluate(obs(change=-4.25), set(), RULES)
        assert t.message == "Pressure falling quickly: -4.2 hPa in 3 h"
        assert (t.device_id, t.value, t.threshold) == ("phone-01", -4.25, -3.5)

    def test_an_open_alert_is_not_raised_again(self):
        assert evaluate(obs(battery=44.0), {("battery_hot", "phone-01")}, RULES) == []


class TestHysteresis:
    def test_between_the_levels_nothing_changes_either_way(self):
        # 40 °C: below the raise level, above the clear level.
        assert evaluate(obs(battery=40.0), set(), RULES) == []
        assert evaluate(obs(battery=40.0), {("battery_hot", "phone-01")}, RULES) == []

    def test_clears_only_past_the_clear_level(self):
        open_ = {("pressure_falling", "phone-01")}
        assert evaluate(obs(change=-2.5), open_, RULES) == []
        assert states(evaluate(obs(change=-1.0), open_, RULES)) == {("pressure_falling", CLEARED)}

    def test_a_device_back_online_clears(self):
        got = evaluate(obs(silent=3), {("device_offline", "esp32-01")}, RULES)
        assert states(got) == {("device_offline", CLEARED)}


class TestUnknowns:
    def test_a_device_never_heard_from_is_not_offline(self):
        assert evaluate(obs(silent=None), set(), RULES) == []

    def test_an_unknown_value_does_not_clear_an_open_alert(self):
        open_ = {("pressure_falling", "phone-01"), ("battery_hot", "phone-01")}
        assert evaluate(obs(change=None, battery=None), open_, RULES) == []
