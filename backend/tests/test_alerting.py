import json
from datetime import datetime, timezone

from psychron.alerting import Alerter
from psychron.domain.alerts import CLEARED, RAISED, Observation

NOW = datetime(2026, 9, 15, 4, 0, tzinfo=timezone.utc)


class MemoryStore:
    def __init__(self, observation: Observation, conflict: bool = False) -> None:
        self.observation = observation
        self.open: set[tuple[str, str]] = set()
        self.conflict = conflict

    def observe(self):
        return self.observation

    def open_alerts(self):
        return set(self.open)

    def apply(self, t):
        if self.conflict:
            return False
        (self.open.add if t.state == RAISED else self.open.discard)((t.kind, t.device_id))
        return True


class MemorySink:
    def __init__(self) -> None:
        self.sent: list[tuple[str, dict, bool]] = []

    def publish(self, topic, payload, retain):
        self.sent.append((topic, json.loads(payload), retain))


def observation(battery):
    return Observation(now=NOW, silent_s={"esp32-01": 1.0}, pressure_change_3h_hpa=0.0,
                       battery_temp_c=battery)


def test_raises_once_then_clears_once_on_a_retained_topic():
    store, sink = MemoryStore(observation(43.0)), MemorySink()
    alerter = Alerter(store, sink)

    assert [t.state for t in alerter.tick()] == [RAISED]
    assert alerter.tick() == []                      # still hot: nothing new to say

    store.observation = observation(38.5)
    assert [t.state for t in alerter.tick()] == [CLEARED]

    topics = {topic for topic, _, _ in sink.sent}
    assert topics == {"psychron/alerts/battery_hot/phone-01"}
    assert all(retain for _, _, retain in sink.sent)
    raised, cleared = (body for _, body, _ in sink.sent)
    assert (raised["state"], raised["value"], raised["at"]) == ("raised", 43.0, "2026-09-15T04:00:00Z")
    assert cleared["state"] == "cleared"


def test_nothing_is_announced_that_was_not_recorded():
    # Another ingestion process won the race to the table: this one stays quiet.
    store, sink = MemoryStore(observation(45.0), conflict=True), MemorySink()
    assert Alerter(store, sink).tick() == []
    assert sink.sent == []


def test_the_payload_keeps_the_degree_sign_readable():
    store, sink = MemoryStore(observation(44.0)), MemorySink()
    Alerter(store, sink).tick()
    assert sink.sent[0][1]["message"] == "Phone battery at 44.0 °C"
