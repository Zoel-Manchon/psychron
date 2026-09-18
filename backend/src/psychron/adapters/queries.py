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

    # How each column becomes one value per bucket, in contract order. Most are a
    # mean and every peak is a maximum, with four exceptions that each have a
    # reason: heading is averaged on the circle (the mean of 359° and 1° is 180°,
    # pointing exactly the wrong way); LAeq is averaged as energy (two seconds at
    # -40 and two at -80 are -43 dB, not -60); and the technology, band and
    # transport are categories, so a bucket takes the most frequent one.
    #
    # The modulo is a doubled percent sign because these strings go through
    # psycopg's placeholder parser, which reads a single one as a parameter.
    _BUCKET = {
        "pressure_hpa": "avg(pressure_hpa)::float8",
        "illuminance_lux": "avg(illuminance_lux)::float8",
        "sound_rms_dbfs": "avg(sound_rms_dbfs)::float8",
        "sound_peak_dbfs": "max(sound_peak_dbfs)::float8",
        "accel_rms": "avg(accel_rms)::float8",
        "accel_peak": "max(accel_peak)::float8",
        "gyro_rms": "avg(gyro_rms)::float8",
        "gyro_peak": "max(gyro_peak)::float8",
        "magnetic_ut": "avg(magnetic_ut)::float8",
        "heading_deg": "(degrees(atan2(avg(sin(radians(heading_deg))),"
                       " avg(cos(radians(heading_deg))))) + 360)::numeric %% 360",
        "battery_temp_c": "avg(battery_temp_c)::float8",
        "lat": "avg(lat)::float8",
        "lon": "avg(lon)::float8",
        "loc_acc_m": "avg(loc_acc_m)::float8",
        "alt_msl_m": "avg(alt_msl_m)::float8",
        "alt_acc_m": "avg(alt_acc_m)::float8",
        "speed_ms": "avg(speed_ms)::float8",
        "noise_laeq_dbfs": "(10 * log(avg(power(10::float8, noise_laeq_dbfs / 10))))::float8",
        "noise_lamax_dbfs": "max(noise_lamax_dbfs)::float8",
        "noise_l10_dbfs": "avg(noise_l10_dbfs)::float8",
        "noise_l90_dbfs": "avg(noise_l90_dbfs)::float8",
        "cell_rat": "mode() WITHIN GROUP (ORDER BY cell_rat)",
        "cell_rsrp_dbm": "avg(cell_rsrp_dbm)::float8",
        "cell_rsrq_db": "avg(cell_rsrq_db)::float8",
        "cell_sinr_db": "avg(cell_sinr_db)::float8",
        "cell_band": "mode() WITHIN GROUP (ORDER BY cell_band)",
        "net_via": "mode() WITHIN GROUP (ORDER BY net_via)",
        "net_vpn": "bool_or(net_vpn)",
        "net_rtt_ms": "avg(net_rtt_ms)::float8",
    }
    SAMPLE_COLUMNS = tuple(_BUCKET)

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
            select = ",\n".join(f"{expr} AS {column}" for column, expr in self._BUCKET.items())
            sql = f"""
                SELECT time_bucket(%s::interval, time) AS t,
                {select}
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

    def recent_altitude(self, device_id: str, at: datetime) -> float | None:
        """The phone's altitude above sea level, if its GNSS has been sure of it.

        The median of the last ten minutes of fixes that claim a vertical accuracy
        of 20 m or better, and only with at least five of them: a single fix
        indoors can be a hundred metres out, and reducing pressure with it would
        move the sea-level figure by twelve hectopascals.
        """
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY alt_msl_m) AS alt, count(*) AS n
                FROM sample
                WHERE device_id = %s AND time BETWEEN %s AND %s
                  AND alt_msl_m IS NOT NULL AND alt_acc_m <= 20
                """,
                (device_id, at - timedelta(minutes=10), at),
            )
            row = cur.fetchone()
        return row["alt"] if row and row["n"] >= 5 else None

    def last_fix(self, device_id: str, at: datetime,
                 within: timedelta = timedelta(hours=24)) -> dict | None:
        """The most recent window that carried a position, without the position.

        For the panel's location tile, which should say how high the phone is and how
        old that is, rather than go blank whenever the latest window happens to have
        no fix. Coordinates stay out of it on purpose: a tile has no use for them, and
        the track that does draws them relative to its own start.
        """
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT time, loc_acc_m, alt_msl_m, alt_acc_m, speed_ms
                FROM sample
                WHERE device_id = %s AND lat IS NOT NULL AND time BETWEEN %s AND %s
                ORDER BY time DESC LIMIT 1
                """,
                (device_id, at - within, at),
            )
            return cur.fetchone()

    def events(self, device_id: str, start: datetime, end: datetime, limit: int = 500) -> list[dict]:
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT time, kind, duration_ms, pga_ms2, sta_lta, freq_hz, quality
                FROM event
                WHERE device_id = %s AND time >= %s AND time < %s
                ORDER BY time DESC LIMIT %s
                """,
                (device_id, start, end, limit),
            )
            return cur.fetchall()

    def alerts(self, recent: int = 20) -> dict:
        """Every open episode, and the most recent ones that have ended."""
        with self._pool.connection() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT kind, device_id, raised_at, cleared_at, value, threshold, message "
                "FROM alert WHERE cleared_at IS NULL ORDER BY raised_at DESC"
            )
            open_ = cur.fetchall()
            cur.execute(
                "SELECT kind, device_id, raised_at, cleared_at, value, threshold, message "
                "FROM alert WHERE cleared_at IS NOT NULL ORDER BY raised_at DESC LIMIT %s",
                (recent,),
            )
            return {"open": open_, "recent": cur.fetchall()}

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
            #
            # The day's own edges count. A gap between two readings needs two
            # readings, so a node that was already down when the window opened
            # leaves nothing for `lag` to subtract from and its outage goes
            # unreported — while the completeness figure beside it says three per
            # cent. Those are two halves of one contradiction, and the half that
            # was missing is usually the larger outage.
            cur.execute(
                """
                WITH span AS (SELECT now() - interval '24 hours' AS opened, now() AS closed),
                     seen AS (
                         SELECT time FROM reading, span
                         WHERE device_id = %s AND time > span.opened
                     ),
                     edges AS (
                         SELECT lag(time) OVER (ORDER BY time) AS gap_start, time AS gap_end
                         FROM seen
                         UNION ALL
                         SELECT (SELECT opened FROM span), (SELECT min(time) FROM seen)
                         UNION ALL
                         SELECT (SELECT max(time) FROM seen), (SELECT closed FROM span)
                     )
                SELECT gap_start, gap_end,
                       -- Cast to float8: EXTRACT yields numeric, which has no JSON
                       -- representation and reaches the client quoted as a string.
                       EXTRACT(EPOCH FROM gap_end - gap_start)::float8 AS seconds
                FROM edges
                WHERE gap_start IS NOT NULL AND gap_end IS NOT NULL
                  AND gap_end - gap_start > interval '30 seconds'
                ORDER BY gap_end DESC LIMIT 20
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
