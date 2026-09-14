"""The boundary of the domain.

Ports are declared here as protocols so the use case in ingest.py depends on
behaviour rather than on psycopg or paho. The practical payoff is that the whole
ingestion path can be exercised against in-memory doubles, with no broker and no
database — which is how the interesting failures get tested at all.
"""

from __future__ import annotations

from datetime import datetime
from typing import Iterable, Protocol

from .domain.alerts import Observation, Transition
from .domain.events import ResolvedEvent
from .domain.samples import ResolvedSample
from .domain.telemetry import ResolvedReading


class ReadingRepository(Protocol):
    def store(self, reading: ResolvedReading) -> bool:
        """Persist a reading. Returns False when it was already there."""

    def store_sample(self, sample: ResolvedSample) -> bool:
        """Persist a v2 window summary. Returns False when it was already there."""

    def store_event(self, event: ResolvedEvent) -> bool:
        """Persist a v2 event. Returns False when it was already there."""

    def store_rejected(self, topic: str, payload: bytes, reason: str,
                       device_id: str | None) -> None:
        """Keep a message that failed validation, with why."""

    def record_boot(self, device_id: str, boot_id: int, firmware: str,
                    reset_reason: str | None) -> None:
        ...

    def known_boot_anchors(self) -> Iterable[tuple[str, int, datetime]]:
        """(device_id, boot_id, boot_epoch) for every boot already anchored.

        Reloaded at startup: without it, restarting ingestion would forget where
        each boot began and place the next replayed batch on arrival time.
        """


class TelemetrySource(Protocol):
    def run(self, on_message) -> None:
        """Deliver (topic, payload, received_at) until stopped."""

    def stop(self) -> None:
        ...


class AlertStore(Protocol):
    def observe(self) -> Observation:
        """What the record says right now, reduced to what the rules read."""

    def open_alerts(self) -> set[tuple[str, str]]:
        """(kind, device_id) for every episode not yet cleared."""

    def apply(self, transition: Transition) -> bool:
        """Record a transition. False if another writer got there first."""


class AlertSink(Protocol):
    def publish(self, topic: str, payload: bytes, retain: bool) -> None:
        """Tell subscribers. Best effort: the alert table is the record."""
