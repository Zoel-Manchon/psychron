"""The ingestion use case: a message arrives, a reading lands or a rejection does.

Depends only on the ports, so the whole path is exercisable with in-memory
doubles — no broker, no database.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from datetime import datetime

from .domain import samples
from .domain.telemetry import BootAnchors, InvalidMessage, parse, resolve
from .ports import ReadingRepository

log = logging.getLogger(__name__)


@dataclass
class IngestStats:
    stored: int = 0
    samples: int = 0
    duplicates: int = 0
    rejected: int = 0
    boots: int = 0


@dataclass
class Ingestor:
    repo: ReadingRepository
    anchors: BootAnchors = field(default_factory=BootAnchors)
    stats: IngestStats = field(default_factory=IngestStats)

    def load_anchors(self) -> int:
        count = 0
        for device_id, boot_id, boot_epoch in self.repo.known_boot_anchors():
            self.anchors.seed(device_id, boot_id, boot_epoch)
            count += 1
        return count

    def handle(self, topic: str, payload: bytes, received_at: datetime) -> None:
        if topic.endswith("/boot"):
            self._handle_boot(topic, payload)
            return

        # An allowlist, not a list of things to skip. Treating every unrecognised
        # topic as telemetry means anything that ever appears under the prefix —
        # a status marker, a probe from a test script, a future subtopic — lands
        # in the rejection table and inflates a number meant to signal trouble.
        #
        # Each message kind is bound to one contract version. A v2 summary under
        # a v1 prefix is not a newer reading; it is something misconfigured, and
        # accepting it would let the topic and the payload disagree silently.
        if topic.startswith("psychron/v2/") and topic.endswith("/sample"):
            self._handle_sample(topic, payload, received_at)
            return
        if not (topic.startswith("psychron/v1/") and topic.endswith("/reading")):
            return

        try:
            msg = parse(payload)
        except InvalidMessage as exc:
            self._reject(topic, payload, str(exc))
            return

        # The topic is a routing label the broker fences on, not proof of who
        # sent this. Identity comes from the payload, and the two disagreeing
        # means something is wrong upstream regardless of which is right.
        expected = self._device_from_topic(topic)
        if expected is not None and expected != msg.device_id:
            self._reject(topic, payload,
                         f"identity mismatch: topic says {expected}, payload says {msg.device_id}")
            return

        reading = resolve(msg, received_at, self.anchors)
        if self.repo.store(reading):
            self.stats.stored += 1
        else:
            self.stats.duplicates += 1

    def _handle_sample(self, topic: str, payload: bytes, received_at: datetime) -> None:
        try:
            msg = samples.parse(payload)
        except InvalidMessage as exc:
            self._reject(topic, payload, str(exc))
            return

        expected = self._device_from_topic(topic)
        if expected is not None and expected != msg.device_id:
            self._reject(topic, payload,
                         f"identity mismatch: topic says {expected}, payload says {msg.device_id}")
            return

        if self.repo.store_sample(samples.resolve(msg, received_at, self.anchors)):
            self.stats.samples += 1
        else:
            self.stats.duplicates += 1

    def _handle_boot(self, topic: str, payload: bytes) -> None:
        try:
            data = json.loads(payload)
            self.repo.record_boot(data["dev"], int(data["boot"]), str(data["fw"]),
                                  data.get("reset"))
            self.stats.boots += 1
        except (ValueError, KeyError, TypeError) as exc:
            self._reject(topic, payload, f"malformed boot announcement: {exc}")

    def _reject(self, topic: str, payload: bytes, reason: str) -> None:
        log.warning("rejected on %s: %s", topic, reason)
        self.repo.store_rejected(topic, payload, reason, self._device_from_topic(topic))
        self.stats.rejected += 1

    @staticmethod
    def _device_from_topic(topic: str) -> str | None:
        parts = topic.split("/")
        return parts[2] if len(parts) >= 3 and parts[0] == "psychron" else None
