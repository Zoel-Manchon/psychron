"""The ingestor's routing, against an in-memory repository.

Two contract versions now share one subscriber, so the routing is where they could
collide: a v2 summary stored as a reading, a v1 reading swallowed by the v2 path, or
a message under the wrong prefix accepted because its payload happened to parse.
"""

import json
from datetime import datetime, timedelta, timezone

from psychron.domain.telemetry import Q_REPLAYED, Q_TIME_FROM_ANCHOR, Q_TIME_FROM_ARRIVAL
from psychron.ingest import Ingestor

T0 = datetime(2026, 9, 14, 12, 0, 0, tzinfo=timezone.utc)
TS = int(T0.timestamp())


class MemoryRepo:
    def __init__(self):
        self.readings, self.samples, self.events, self.rejected = [], [], [], []

    def store_event(self, event):
        m = event.message
        key = (m.device_id, m.boot_id, m.seq)
        if any((e.message.device_id, e.message.boot_id, e.message.seq) == key for e in self.events):
            return False
        self.events.append(event)
        return True

    def store(self, reading):
        key = (reading.device_id, reading.boot_id, reading.seq)
        if any((r.device_id, r.boot_id, r.seq) == key for r in self.readings):
            return False
        self.readings.append(reading)
        return True

    def store_sample(self, sample):
        key = (sample.device_id, sample.boot_id, sample.seq)
        if any((s.device_id, s.boot_id, s.seq) == key for s in self.samples):
            return False
        self.samples.append(sample)
        return True

    def store_rejected(self, topic, payload, reason, device_id):
        self.rejected.append((topic, reason))

    def record_boot(self, *args):
        pass

    def known_boot_anchors(self):
        return []


def v1(**over):
    base = {"v": 1, "dev": "esp32-01", "fw": "1.0.1", "boot": 7, "seq": 1,
            "ts": TS, "up": 1000, "t": 23.4, "h": 55.0, "q": 0}
    base.update(over)
    return json.dumps(base).encode()


def v2(**over):
    base = {"v": 2, "dev": "phone-01", "fw": "0.1.0", "boot": 9, "seq": 1,
            "ts": TS, "up": 2000, "win": 2000, "q": 0,
            "baro": {"hpa": 1012.8}, "batt": {"c": 30.1}}
    base.update(over)
    return json.dumps(base).encode()


def event(**over):
    base = {"v": 2, "dev": "phone-01", "fw": "android-0.5.0", "boot": 9, "seq": 1,
            "ts": TS, "up": 2500, "q": 0, "kind": "vibration", "dur": 900, "pga": 0.3, "ratio": 5.0}
    base.update(over)
    return json.dumps(base).encode()


def ingest(topic, payload):
    repo = MemoryRepo()
    ing = Ingestor(repo)
    ing.handle(topic, payload, T0)
    return repo, ing


def test_v1_readings_still_land_where_they_did():
    repo, ing = ingest("psychron/v1/esp32-01/reading", v1())
    assert len(repo.readings) == 1 and not repo.samples and not repo.rejected
    assert ing.stats.stored == 1


def test_v2_samples_land_in_their_own_table():
    repo, ing = ingest("psychron/v2/phone-01/sample", v2())
    assert len(repo.samples) == 1 and not repo.readings and not repo.rejected
    assert repo.samples[0].measurements.pressure_hpa == 1012.8
    assert ing.stats.samples == 1


def test_a_v1_payload_on_a_v2_topic_is_rejected_not_stored():
    repo, _ = ingest("psychron/v2/phone-01/sample", v1(dev="phone-01"))
    assert not repo.readings and not repo.samples
    assert "unsupported contract version" in repo.rejected[0][1]


def test_a_sample_under_the_v1_prefix_is_ignored():
    # Not rejected either: the allowlist does not recognise it as telemetry, so
    # it must not inflate the rejection count that signals real trouble.
    repo, _ = ingest("psychron/v1/phone-01/sample", v2())
    assert not repo.samples and not repo.readings and not repo.rejected


def test_a_reading_under_the_v2_prefix_is_ignored():
    repo, _ = ingest("psychron/v2/esp32-01/reading", v1())
    assert not repo.readings and not repo.rejected


def test_the_payload_must_match_the_topic_identity():
    repo, _ = ingest("psychron/v2/phone-01/sample", v2(dev="phone-02"))
    assert not repo.samples
    assert "identity mismatch" in repo.rejected[0][1]


def test_a_redelivered_sample_is_a_duplicate():
    repo = MemoryRepo()
    ing = Ingestor(repo)
    ing.handle("psychron/v2/phone-01/sample", v2(), T0)
    ing.handle("psychron/v2/phone-01/sample", v2(), T0)
    assert len(repo.samples) == 1
    assert ing.stats.samples == 1 and ing.stats.duplicates == 1


def test_events_land_in_their_own_table():
    repo, ing = ingest("psychron/v2/phone-01/event", event())
    assert len(repo.events) == 1 and not repo.samples and not repo.rejected
    assert ing.stats.events == 1


def test_a_sample_on_the_event_topic_is_rejected_not_stored():
    repo, _ = ingest("psychron/v2/phone-01/event", v2())
    assert not repo.events and not repo.samples
    assert "kind must be one of" in repo.rejected[0][1]


def test_an_event_and_a_sample_may_share_a_sequence_number():
    # Separate counters on the node, separate identities here.
    repo = MemoryRepo()
    ing = Ingestor(repo)
    ing.handle("psychron/v2/phone-01/sample", v2(seq=3), T0)
    ing.handle("psychron/v2/phone-01/event", event(seq=3), T0)
    assert len(repo.samples) == 1 and len(repo.events) == 1 and ing.stats.duplicates == 0


def test_readings_and_samples_share_a_boot_anchor_table_without_colliding():
    # Same boot id on two different devices must stay two different boots.
    repo = MemoryRepo()
    ing = Ingestor(repo)
    ing.handle("psychron/v1/esp32-01/reading", v1(boot=5, up=1000), T0)
    ing.handle("psychron/v2/phone-01/sample", v2(boot=5, up=900000), T0)
    assert ing.anchors.instant_for(repo.readings[0]) == T0
    assert ing.anchors.instant_for(repo.samples[0]) == T0


def test_a_night_buffered_on_mobile_data_arrives_at_the_times_it_was_measured():
    """What the outbox is for, end to end, with nothing seeded.

    The phone leaves the Wi-Fi, keeps measuring, and finds no way home for hours.
    Every window waits on disk. It comes back, flushes them in a burst, and this
    is the moment the record is either a night of measurements or a spike: the
    ingestor has no anchor for that boot, because no message from it ever arrived
    while the phone was live. Their own timestamps are all there is, and they are
    enough.
    """
    repo = MemoryRepo()
    ing = Ingestor(repo)
    night = [T0 - timedelta(hours=h) for h in (8, 6, 4, 2)]
    flush = T0
    for i, taken in enumerate(night):
        ing.handle("psychron/v2/phone-01/sample",
                   v2(seq=100 + i, ts=int(taken.timestamp()), up=(8 - 2 * i) * 3_600_000,
                      q=Q_REPLAYED, net={"via": "cell", "vpn": False}),
                   flush + timedelta(milliseconds=i * 30))

    assert [s.time for s in repo.samples] == night
    assert all(s.quality & Q_REPLAYED for s in repo.samples), "the replay stays on the record"
    assert not any(s.quality & (Q_TIME_FROM_ANCHOR | Q_TIME_FROM_ARRIVAL) for s in repo.samples)
