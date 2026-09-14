"""The alerting use case: look at the record, decide, keep the verdict, tell the nodes.

Depends only on ports, like ingest.py, so the whole loop runs against in-memory
doubles in the tests.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field

from .domain.alerts import Rule, Transition, default_rules, evaluate
from .ports import AlertSink, AlertStore

log = logging.getLogger(__name__)


def topic_for(t: Transition) -> str:
    return f"psychron/alerts/{t.kind}/{t.device_id}"


def payload_for(t: Transition) -> bytes:
    return json.dumps({
        "kind": t.kind, "device": t.device_id, "state": t.state,
        "at": t.at.isoformat().replace("+00:00", "Z"),
        "value": round(t.value, 2), "threshold": t.threshold, "message": t.message,
    }, ensure_ascii=False).encode("utf-8")


@dataclass
class Alerter:
    store: AlertStore
    sink: AlertSink
    rules: list[Rule] = field(default_factory=default_rules)

    def tick(self) -> list[Transition]:
        transitions = evaluate(self.store.observe(), self.store.open_alerts(), self.rules)
        applied: list[Transition] = []
        for t in transitions:
            # Recorded first and announced second. A notification for an alert that
            # failed to be stored would describe something the record does not hold.
            if not self.store.apply(t):
                continue
            # Retained, so a phone that connects later hears the current state
            # rather than silence, and a cleared alert replaces the raised one.
            self.sink.publish(topic_for(t), payload_for(t), retain=True)
            log.info("alert %s: %s", t.state, t.message)
            applied.append(t)
        return applied
