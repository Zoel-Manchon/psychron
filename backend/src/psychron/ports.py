"""The boundary of the domain.

Ports are declared here as protocols so the use case in ingest.py depends on
behaviour rather than on psycopg or paho. The practical payoff is that the whole
ingestion path can be exercised against in-memory doubles, with no broker and no
database — which is how the interesting failures get tested at all.
"""

from __future__ import annotations

from datetime import datetime
from typing import Iterable, Protocol

from .domain.telemetry import ResolvedReading


class ReadingRepository(Protocol):
    def store(self, reading: ResolvedReading) -> bool:
        """Persist a reading. Returns False when it was already there."""

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
