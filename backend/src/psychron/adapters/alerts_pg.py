"""Where the alert rules get their numbers, and where their verdicts are kept."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import psycopg
from psycopg.rows import dict_row

from ..domain.alerts import CLEARED, RAISED, Observation, Transition


class PostgresAlertStore:
    """A connection of its own. The evaluator runs on a timer thread beside the
    MQTT callback thread, and a psycopg connection is not something two threads
    may use at once."""

    def __init__(self, dsn: str, esp32: str = "esp32-01", phone: str = "phone-01") -> None:
        self._conn = psycopg.connect(dsn, autocommit=True, row_factory=dict_row)
        self._esp32, self._phone = esp32, phone

    def close(self) -> None:
        self._conn.close()

    def observe(self) -> Observation:
        now = datetime.now(timezone.utc)
        with self._conn.cursor() as cur:
            # Arrival, not measurement time: a node replaying an hour of backlog is
            # online, and one whose clock runs ahead is not therefore silent.
            cur.execute("SELECT max(received_at) AS at FROM reading WHERE device_id = %s",
                        (self._esp32,))
            esp32_at = cur.fetchone()["at"]
            cur.execute("SELECT max(received_at) AS at FROM sample WHERE device_id = %s",
                        (self._phone,))
            phone_at = cur.fetchone()["at"]

            # Latest pressure and battery, each only if recent: a battery reading
            # from last night is not the phone's temperature now.
            cur.execute(
                """
                SELECT
                  (SELECT pressure_hpa FROM sample
                    WHERE device_id = %(d)s AND pressure_hpa IS NOT NULL AND time > %(p)s
                    ORDER BY time DESC LIMIT 1) AS hpa,
                  (SELECT avg(pressure_hpa)::float8 FROM sample
                    WHERE device_id = %(d)s AND pressure_hpa IS NOT NULL
                      AND time BETWEEN %(t0)s AND %(t1)s) AS hpa_3h,
                  (SELECT battery_temp_c FROM sample
                    WHERE device_id = %(d)s AND battery_temp_c IS NOT NULL AND time > %(b)s
                    ORDER BY time DESC LIMIT 1) AS battery
                """,
                {"d": self._phone, "p": now - timedelta(minutes=10),
                 "t0": now - timedelta(hours=3, minutes=10), "t1": now - timedelta(hours=2, minutes=50),
                 "b": now - timedelta(minutes=2)},
            )
            row = cur.fetchone()

        def silent(at: datetime | None) -> float | None:
            return None if at is None else max(0.0, (now - at).total_seconds())

        change = None if row["hpa"] is None or row["hpa_3h"] is None else row["hpa"] - row["hpa_3h"]
        return Observation(now=now,
                           silent_s={self._esp32: silent(esp32_at), self._phone: silent(phone_at)},
                           pressure_change_3h_hpa=change, battery_temp_c=row["battery"])

    def open_alerts(self) -> set[tuple[str, str]]:
        with self._conn.cursor() as cur:
            cur.execute("SELECT kind, device_id FROM alert WHERE cleared_at IS NULL")
            return {(r["kind"], r["device_id"]) for r in cur.fetchall()}

    def apply(self, t: Transition) -> bool:
        with self._conn.cursor() as cur:
            if t.state == RAISED:
                # The partial unique index is the arbiter: if another process
                # raised the same alert first, this insert quietly does nothing.
                cur.execute(
                    """
                    INSERT INTO alert (kind, device_id, raised_at, value, threshold, message)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    ON CONFLICT (kind, device_id) WHERE cleared_at IS NULL DO NOTHING
                    """,
                    (t.kind, t.device_id, t.at, t.value, t.threshold, t.message),
                )
            elif t.state == CLEARED:
                cur.execute(
                    "UPDATE alert SET cleared_at = %s WHERE kind = %s AND device_id = %s "
                    "AND cleared_at IS NULL",
                    (t.at, t.kind, t.device_id),
                )
            return cur.rowcount > 0
