"""PostgreSQL/TimescaleDB adapter."""

from __future__ import annotations

from datetime import datetime
from typing import Iterable

import psycopg
from psycopg.rows import tuple_row

from dataclasses import fields

from ..domain.samples import Measurements, ResolvedSample
from ..domain.telemetry import (
    Q_TIME_FROM_ANCHOR,
    Q_TIME_FROM_ARRIVAL,
    ResolvedReading,
)

# Bits that mean the instant was inferred rather than measured. A reading with
# either one set is not a safe basis for anchoring a boot.
_INFERRED = Q_TIME_FROM_ANCHOR | Q_TIME_FROM_ARRIVAL

# Taken from the dataclass rather than written out, so a quantity added to the
# contract cannot be validated on the wire and then quietly missing from INSERT.
_MEASUREMENT_COLUMNS = [f.name for f in fields(Measurements)]


class PostgresReadingRepository:
    def __init__(self, dsn: str) -> None:
        self._conn = psycopg.connect(dsn, autocommit=True, row_factory=tuple_row)

    def close(self) -> None:
        self._conn.close()

    def ensure_device(self, device_id: str, label: str, sensor_model: str) -> None:
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO device (device_id, label, sensor_model) VALUES (%s, %s, %s) "
                "ON CONFLICT (device_id) DO NOTHING",
                (device_id, label, sensor_model),
            )

    def store(self, reading: ResolvedReading) -> bool:
        # Identity is checked on (device, boot, seq) rather than left to the
        # unique index, which has to include time because the table is
        # partitioned on it. A redelivery whose instant resolved differently the
        # second time would slip past that index and be stored twice.
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM reading WHERE device_id = %s AND boot_id = %s AND seq = %s LIMIT 1",
                (reading.device_id, reading.boot_id, reading.seq),
            )
            if cur.fetchone() is not None:
                return False

            cur.execute(
                """
                INSERT INTO reading (time, device_id, boot_id, seq, device_time,
                                     received_at, uptime_ms, temperature_c,
                                     humidity_pct, quality, firmware)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT DO NOTHING
                """,
                (reading.time, reading.device_id, reading.boot_id, reading.seq,
                 reading.device_time, reading.received_at, reading.uptime_ms,
                 reading.temperature_c, reading.humidity_pct, reading.quality,
                 reading.firmware),
            )
            return cur.rowcount > 0

    def store_sample(self, sample: ResolvedSample) -> bool:
        # Same reasoning as store(): the identity is (device, boot, seq), and the
        # unique index cannot say so alone because it has to include time.
        with self._conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM sample WHERE device_id = %s AND boot_id = %s AND seq = %s LIMIT 1",
                (sample.device_id, sample.boot_id, sample.seq),
            )
            if cur.fetchone() is not None:
                return False

            envelope = ["time", "device_id", "boot_id", "seq", "device_time",
                        "received_at", "uptime_ms", "window_ms", "quality", "firmware"]
            columns = envelope + _MEASUREMENT_COLUMNS
            values = [sample.time, sample.device_id, sample.boot_id, sample.seq,
                      sample.device_time, sample.received_at, sample.uptime_ms,
                      sample.window_ms, sample.quality, sample.firmware]
            values += [getattr(sample.measurements, c) for c in _MEASUREMENT_COLUMNS]
            cur.execute(
                f"INSERT INTO sample ({', '.join(columns)}) "
                f"VALUES ({', '.join(['%s'] * len(columns))}) ON CONFLICT DO NOTHING",
                values,
            )
            return cur.rowcount > 0

    def store_rejected(self, topic: str, payload: bytes, reason: str,
                       device_id: str | None) -> None:
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO rejected_message (topic, payload, reason, device_id) "
                "VALUES (%s, %s, %s, %s)",
                (topic, payload, reason[:500], device_id),
            )

    def record_boot(self, device_id: str, boot_id: int, firmware: str,
                    reset_reason: str | None) -> None:
        with self._conn.cursor() as cur:
            cur.execute(
                "INSERT INTO device_boot (device_id, boot_id, firmware, reset_reason) "
                "VALUES (%s, %s, %s, %s) ON CONFLICT (device_id, boot_id) DO NOTHING",
                (device_id, boot_id, firmware, reset_reason),
            )

    def known_boot_anchors(self) -> Iterable[tuple[str, int, datetime]]:
        with self._conn.cursor() as cur:
            cur.execute(
                """
                SELECT DISTINCT ON (device_id, boot_id)
                       device_id, boot_id,
                       time - make_interval(secs => uptime_ms / 1000.0)
                FROM (
                    -- Both contracts anchor boots the same way, so a restart
                    -- must reload anchors from both; otherwise a phone window
                    -- replayed after ingestion restarts lands on arrival time.
                    SELECT device_id, boot_id, time, uptime_ms, device_time, quality FROM reading
                    UNION ALL
                    SELECT device_id, boot_id, time, uptime_ms, device_time, quality FROM sample
                ) observed
                WHERE device_time IS NOT NULL AND (quality & %s) = 0
                ORDER BY device_id, boot_id, time ASC
                """,
                (_INFERRED,),
            )
            return list(cur.fetchall())

    # ── read side, used by the status view ──────────────────────────────────

    def summary(self, device_id: str | None = None) -> dict:
        where = "WHERE device_id = %s" if device_id else ""
        args = (device_id,) if device_id else ()
        with self._conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT count(*), min(time), max(time),
                       count(*) FILTER (WHERE temperature_c IS NULL),
                       count(*) FILTER (WHERE quality <> 0)
                FROM reading {where}
                """,
                args,
            )
            total, first, last, nulls, flagged = cur.fetchone()

            recent_where = f"{where} AND" if where else "WHERE"
            cur.execute(
                f"SELECT count(*) FROM reading {recent_where} "
                "time > now() - interval '5 minutes'", args)
            recent = cur.fetchone()[0]

            cur.execute("SELECT count(*) FROM rejected_message")
            rejected = cur.fetchone()[0]

            cur.execute(
                f"""
                SELECT time, temperature_c, humidity_pct, quality, firmware
                FROM reading {where} ORDER BY time DESC LIMIT 1
                """,
                args,
            )
            latest = cur.fetchone()

        return {
            "total": total, "first": first, "last": last, "nulls": nulls,
            "flagged": flagged, "recent_5m": recent, "rejected": rejected,
            "latest": latest,
        }

    def recent(self, limit: int = 10, device_id: str | None = None) -> list[tuple]:
        where = "WHERE device_id = %s" if device_id else ""
        args = ((device_id, limit) if device_id else (limit,))
        with self._conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT time, temperature_c, humidity_pct, quality, seq
                FROM reading {where} ORDER BY time DESC LIMIT %s
                """,
                args,
            )
            return cur.fetchall()
