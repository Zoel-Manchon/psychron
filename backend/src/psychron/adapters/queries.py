"""Read-side queries for the API.

Separate from the ingestion repository on purpose: the write path cares about
one row at a time and idempotency, the read path cares about bounded result
sets. They share a table and nothing else.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Iterator

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

# Which pre-aggregated view answers a given span. A month of raw readings at one
# every three seconds is 860,000 rows; nobody can plot that and no browser should
# be asked to. The bucket is chosen from the span so a request can never ask for
# more than the caller could possibly use, whatever dates they type.
_LADDER = [
    (timedelta(hours=3), None, "3s"),
    (timedelta(days=2), "reading_1m", "1m"),
    (timedelta(days=60), "reading_1h", "1h"),
    (None, "reading_1d", "1d"),
]

MAX_POINTS = 5000


@dataclass(frozen=True)
class Series:
    bucket: str
    points: list[dict]
    truncated: bool


def choose_bucket(start: datetime, end: datetime) -> tuple[str | None, str]:
    span = end - start
    for limit, view, label in _LADDER:
        if limit is None or span <= limit:
            return view, label
    return "reading_1d", "1d"


class ReadQueries:
    def __init__(self, dsn: str) -> None:
        # A pool, not a connection. An export holds a server-side cursor open
        # for as long as it takes to stream, and anything else querying the same
        # connection meanwhile corrupts the protocol mid-response — the download
        # simply stops, chunked and truncated, with no error anywhere. The
        # dashboard's parallel fetches survived a single connection only because
        # psycopg serialises them behind a lock; a long cursor does not.
        self._pool = ConnectionPool(dsn, min_size=1, max_size=8, open=True,
                                    kwargs={"autocommit": True, "row_factory": dict_row})

    def close(self) -> None:
        self._pool.close()

    # ── current ─────────────────────────────────────────────────────────────

    def latest(self, device_id: str) -> dict | None:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT time, temperature_c, humidity_pct, quality, firmware,
                       boot_id, seq, device_time, received_at
                FROM reading WHERE device_id = %s ORDER BY time DESC LIMIT 1
                """,
                (device_id,),
            )
            return cur.fetchone()

    # ── series ──────────────────────────────────────────────────────────────

    def series(self, device_id: str, start: datetime, end: datetime) -> Series:
        view, label = choose_bucket(start, end)

        if view is None:
            sql = """
                SELECT time AS t, temperature_c AS temp, humidity_pct AS hum,
                       NULL::float8 AS temp_min, NULL::float8 AS temp_max, quality
                FROM reading
                WHERE device_id = %s AND time >= %s AND time < %s
                ORDER BY time LIMIT %s
            """
        else:
            sql = f"""
                SELECT bucket AS t, temp_avg AS temp, hum_avg AS hum,
                       temp_min, temp_max, 0 AS quality
                FROM {view}
                WHERE device_id = %s AND bucket >= %s AND bucket < %s
                ORDER BY bucket LIMIT %s
            """

        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(sql, (device_id, start, end, MAX_POINTS + 1))
            rows = cur.fetchall()

        truncated = len(rows) > MAX_POINTS
        return Series(bucket=label, points=rows[:MAX_POINTS], truncated=truncated)

    # ── phone node (contract v2) ────────────────────────────────────────────

    SAMPLE_COLUMNS = ("pressure_hpa", "illuminance_lux", "sound_rms_dbfs", "sound_peak_dbfs",
                      "accel_rms", "accel_peak", "gyro_rms", "gyro_peak",
                      "magnetic_ut", "heading_deg", "battery_temp_c")

    def sample_latest(self, device_id: str) -> dict | None:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT time, received_at, device_time, boot_id, seq, window_ms,
                       quality, firmware, {", ".join(self.SAMPLE_COLUMNS)}
                FROM sample WHERE device_id = %s ORDER BY time DESC LIMIT 1
                """,
                (device_id,),
            )
            return cur.fetchone()

    def pressure_tendency(self, device_id: str, now_hpa: float, at: datetime) -> float | None:
        """Change in pressure over the last three hours, in hPa.

        Three hours because that is the interval meteorology reports tendency
        over, which is what makes the number comparable with a forecast's. None
        when there is no pressure recorded near that instant: a tendency computed
        against whatever reading happened to be closest would invent a trend.
        """
        target = at - timedelta(hours=3)
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT avg(pressure_hpa)::float8 AS hpa
                FROM sample
                WHERE device_id = %s AND pressure_hpa IS NOT NULL
                  AND time BETWEEN %s AND %s
                """,
                (device_id, target - timedelta(minutes=10), target + timedelta(minutes=10)),
            )
            row = cur.fetchone()
        return None if not row or row["hpa"] is None else now_hpa - row["hpa"]

    # Computed on the fly with time_bucket rather than from continuous aggregates:
    # at one window every two seconds the volume does not justify them yet, and
    # adding them later changes no endpoint.
    _SAMPLE_LADDER = [
        (timedelta(hours=1), None, "2s"),
        (timedelta(hours=12), "30 seconds", "30s"),
        (timedelta(days=2), "2 minutes", "2m"),
        (timedelta(days=60), "1 hour", "1h"),
        (None, "1 day", "1d"),
    ]

    def sample_series(self, device_id: str, start: datetime, end: datetime) -> Series:
        span = end - start
        # With slack. The panel asks for "the last hour" by computing now minus an
        # hour in the browser; by the time the server measures the span it is an
        # hour and a few milliseconds, and without slack every preset lands one
        # tier too coarse. The raw tier would be unreachable from the UI — and the
        # raw tier is the only one the live feed can append to.
        slack = timedelta(minutes=5)
        interval, label = next((i, l) for limit, i, l in self._SAMPLE_LADDER
                               if limit is None or span <= limit + slack)

        if interval is None:
            select = "time AS t, " + ", ".join(self.SAMPLE_COLUMNS)
            sql = f"""
                SELECT {select} FROM sample
                WHERE device_id = %s AND time >= %s AND time < %s
                ORDER BY time LIMIT %s
            """
            params: tuple = (device_id, start, end, MAX_POINTS + 1)
        else:
            # Peaks take the maximum of the bucket and everything else the mean,
            # except heading. The mean of 359° and 1° is 180°, pointing exactly
            # the wrong way, so heading is averaged on the circle instead.
            #
            # The modulo is written as a doubled percent sign because this string
            # goes through psycopg's placeholder parser, which reads a single one
            # as the start of a parameter. It parses SQL comments too, so this
            # explanation has to live out here rather than next to the operator.
            sql = """
                SELECT time_bucket(%s::interval, time) AS t,
                       avg(pressure_hpa)::float8    AS pressure_hpa,
                       avg(illuminance_lux)::float8 AS illuminance_lux,
                       avg(sound_rms_dbfs)::float8  AS sound_rms_dbfs,
                       max(sound_peak_dbfs)::float8 AS sound_peak_dbfs,
                       avg(accel_rms)::float8       AS accel_rms,
                       max(accel_peak)::float8      AS accel_peak,
                       avg(gyro_rms)::float8        AS gyro_rms,
                       max(gyro_peak)::float8       AS gyro_peak,
                       avg(magnetic_ut)::float8     AS magnetic_ut,
                       (degrees(atan2(avg(sin(radians(heading_deg))),
                                      avg(cos(radians(heading_deg))))) + 360)::numeric
                           %% 360                   AS heading_deg,
                       avg(battery_temp_c)::float8  AS battery_temp_c
                FROM sample
                WHERE device_id = %s AND time >= %s AND time < %s
                GROUP BY 1 ORDER BY 1 LIMIT %s
            """
            params = (interval, device_id, start, end, MAX_POINTS + 1)

        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()
        for row in rows:
            if row.get("heading_deg") is not None:
                row["heading_deg"] = float(row["heading_deg"])
        truncated = len(rows) > MAX_POINTS
        return Series(bucket=label, points=rows[:MAX_POINTS], truncated=truncated)

    # ── statistics ──────────────────────────────────────────────────────────

    def stats(self, device_id: str, start: datetime, end: datetime) -> dict:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT count(*) AS samples,
                       count(*) FILTER (WHERE temperature_c IS NULL) AS failed,
                       count(*) FILTER (WHERE quality <> 0) AS flagged,
                       min(temperature_c) AS temp_min,
                       max(temperature_c) AS temp_max,
                       avg(temperature_c) AS temp_mean,
                       stddev_samp(temperature_c) AS temp_sd,
                       percentile_cont(0.05) WITHIN GROUP (ORDER BY temperature_c) AS temp_p05,
                       percentile_cont(0.50) WITHIN GROUP (ORDER BY temperature_c) AS temp_median,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY temperature_c) AS temp_p95,
                       min(humidity_pct) AS hum_min,
                       max(humidity_pct) AS hum_max,
                       avg(humidity_pct) AS hum_mean,
                       stddev_samp(humidity_pct) AS hum_sd,
                       percentile_cont(0.05) WITHIN GROUP (ORDER BY humidity_pct) AS hum_p05,
                       percentile_cont(0.50) WITHIN GROUP (ORDER BY humidity_pct) AS hum_median,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY humidity_pct) AS hum_p95
                FROM reading
                WHERE device_id = %s AND time >= %s AND time < %s
                """,
                (device_id, start, end),
            )
            row = cur.fetchone() or {}

        # Completeness, not just a count. A window with 900 readings means
        # nothing until you know whether 900 or 28,800 were expected, and that
        # difference is exactly what an outage looks like in the data.
        expected = max(1, int((end - start).total_seconds() // 3))
        row["expected_samples"] = expected
        row["completeness"] = min(1.0, (row.get("samples") or 0) / expected)
        return row

    # ── the record as a whole ───────────────────────────────────────────────

    def extent(self, device_id: str) -> dict:
        """How far back the record goes, and how much of it there is.

        Window statistics answer "what has the room been doing lately". This
        answers the question the project is actually about: how much elapsed
        time has been captured and cannot be captured again.
        """
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT min(time) AS first, max(time) AS last, count(*) AS total"
                "  FROM reading WHERE device_id = %s",
                (device_id,),
            )
            return cur.fetchone() or {}

    def near(self, device_id: str, target: datetime,
             tolerance: timedelta = timedelta(minutes=30)) -> dict | None:
        """The reading closest to an instant, or nothing if none is close.

        Bounded on both sides before ordering, so this reads a slice the time
        index can find rather than sorting the table. Returning None when the
        nearest reading is half an hour away is the point: a comparison against
        a value from some other day is worse than no comparison.
        """
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT time, temperature_c, humidity_pct
                FROM reading
                WHERE device_id = %s AND time BETWEEN %s AND %s
                ORDER BY abs(EXTRACT(EPOCH FROM time - %s))
                LIMIT 1
                """,
                (device_id, target - tolerance, target + tolerance, target),
            )
            return cur.fetchone()

    # ── device health ───────────────────────────────────────────────────────

    def device_health(self, device_id: str) -> dict:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT boot_id, firmware, reset_reason, first_seen
                FROM device_boot WHERE device_id = %s
                ORDER BY first_seen DESC LIMIT 5
                """,
                (device_id,),
            )
            boots = cur.fetchall()

            cur.execute(
                """
                SELECT count(*) AS rejected_24h
                FROM rejected_message
                WHERE received_at > now() - interval '24 hours'
                """
            )
            rejected = cur.fetchone()

            # Gaps are found from the data rather than reported by the device,
            # because a node that dies cannot tell anyone it died.
            cur.execute(
                """
                SELECT prev_time AS gap_start, time AS gap_end,
                       -- Cast to float8: EXTRACT yields numeric, which has no JSON
                       -- representation and reaches the client quoted as a string.
                       EXTRACT(EPOCH FROM time - prev_time)::float8 AS seconds
                FROM (
                    SELECT time, lag(time) OVER (ORDER BY time) AS prev_time
                    FROM reading
                    WHERE device_id = %s AND time > now() - interval '24 hours'
                ) s
                WHERE prev_time IS NOT NULL AND time - prev_time > interval '30 seconds'
                ORDER BY time DESC LIMIT 20
                """,
                (device_id,),
            )
            gaps = cur.fetchall()

        return {"boots": boots, "gaps_24h": gaps, **(rejected or {})}

    # ── export ──────────────────────────────────────────────────────────────

    def stream_readings(self, device_id: str, start: datetime,
                        end: datetime) -> Iterator[dict]:
        """Raw readings for export, streamed rather than gathered.

        Exports are the one place an unbounded result is legitimate — the caller
        asked for the data — but it still must not be assembled in memory first.
        """
        # A server-side cursor is a DECLARE, and DECLARE is only legal inside a
        # transaction block. The pool hands out autocommit connections, so the
        # transaction has to be opened explicitly here — without it the cursor
        # raises, the streaming generator dies after the header rows, and the
        # client sees a truncated download with no error attached to it.
        with self._pool.connection() as conn, conn.transaction(), \
                conn.cursor(name=f"export_{id(self)}") as cur:
            cur.itersize = 2000
            cur.execute(
                """
                SELECT time, device_id, boot_id, seq, device_time, received_at,
                       uptime_ms, temperature_c, humidity_pct, quality, firmware
                FROM reading
                WHERE device_id = %s AND time >= %s AND time < %s
                ORDER BY time
                """,
                (device_id, start, end),
            )
            yield from cur

    def count_readings(self, device_id: str, start: datetime, end: datetime) -> int:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) AS n FROM reading "
                "WHERE device_id = %s AND time >= %s AND time < %s",
                (device_id, start, end),
            )
            return (cur.fetchone() or {}).get("n", 0)
