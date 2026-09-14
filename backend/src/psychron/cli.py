"""Command line: run ingestion, or look at what has actually landed."""

from __future__ import annotations

import argparse
import logging
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from .adapters.mqtt import MqttTelemetrySource
from .adapters.postgres import PostgresReadingRepository
from .ingest import Ingestor

# On the host the file sits beside the compose stack it configures. In a
# container that relative walk lands nowhere, so the path is overridable —
# the file is mounted at a fixed place and named explicitly. Either way
# there is exactly one file holding the credentials.
DEFAULT_ENV = Path(os.environ.get(
    "PSYCHRON_ENV_FILE", Path(__file__).resolve().parents[3] / "infra" / ".env"))


def load_env(path: Path = DEFAULT_ENV) -> dict[str, str]:
    """Read infra/.env so credentials stay in one place and out of the source."""
    values: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, _, val = line.partition("=")
                values[key.strip()] = val.strip()
    values.update({k: v for k, v in os.environ.items() if k in values or k.startswith("PSYCHRON_")})
    return values


def dsn_from(env: dict[str, str]) -> str:
    password = env.get("POSTGRES_PASSWORD", "")
    host = env.get("PSYCHRON_DB_HOST", "127.0.0.1")
    return f"postgresql://psychron:{password}@{host}:5432/psychron"


def _fmt_age(when: datetime | None) -> str:
    if when is None:
        return "never"
    seconds = (datetime.now(timezone.utc) - when).total_seconds()
    if seconds < 90:
        return f"{seconds:.0f}s ago"
    if seconds < 5400:
        return f"{seconds / 60:.0f}m ago"
    return f"{seconds / 3600:.1f}h ago"


def render_status(repo: PostgresReadingRepository, device: str | None) -> str:
    s = repo.summary(device)
    lines = []
    lines.append("psychron ingest status")
    lines.append("")
    lines.append(f"  readings stored     {s['total']:,}")
    lines.append(f"  in the last 5 min   {s['recent_5m']:,}")
    lines.append(f"  oldest              {s['first'] or '-'}")
    lines.append(f"  newest              {s['last'] or '-'}  ({_fmt_age(s['last'])})")
    lines.append(f"  null readings       {s['nulls']:,}")
    lines.append(f"  quality-flagged     {s['flagged']:,}")
    lines.append(f"  rejected messages   {s['rejected']:,}")

    if s["latest"]:
        when, temp, hum, quality, firmware = s["latest"]
        t = f"{temp:.2f}" if temp is not None else "null"
        h = f"{hum:.2f}" if hum is not None else "null"
        lines.append("")
        lines.append(f"  latest              {t} C   {h} %   q=0x{quality:04x}   fw {firmware}")

    rows = repo.recent(8, device)
    if rows:
        lines.append("")
        lines.append("  recent")
        lines.append("    time                      temp     hum   seq   quality")
        for when, temp, hum, quality, seq in rows:
            t = f"{temp:7.2f}" if temp is not None else "   null"
            h = f"{hum:6.2f}" if hum is not None else "  null"
            lines.append(f"    {when:%Y-%m-%d %H:%M:%S}   {t}  {h}  {seq:>5}   0x{quality:04x}")

    if s["total"] == 0:
        lines.append("")
        lines.append("  Nothing stored yet. Either the node is not publishing, or")
        lines.append("  ingestion is not running: check `psychron run`.")
    return "\n".join(lines)


def fail(msg: str) -> int:
    print(msg, file=sys.stderr)
    return 1


def cmd_run(args) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    env = load_env()

    repo = PostgresReadingRepository(dsn_from(env))
    repo.ensure_device(args.device, "Desk node", "DHT22")
    # The phone node publishes contract v2. Its row exists before its first
    # message, because the sample table's foreign key would reject that message.
    repo.ensure_device(env.get("PSYCHRON_PHONE_DEVICE", "phone-01"),
                       "Galaxy S26", "Android SensorManager")

    ingestor = Ingestor(repo)
    restored = ingestor.load_anchors()
    logging.info("restored %d boot anchor(s) from the database", restored)

    # Certificates are the only way in: the broker has no plaintext listener and
    # no password file. Missing material is a hard stop rather than a warning,
    # because there is nothing left to fall back to and retrying a connection
    # that cannot succeed only buries the reason in the log.
    certs = Path(env.get("PSYCHRON_CERT_DIR", str(DEFAULT_ENV.parent / "certs")))
    ca, crt, key = certs / "ca.crt", certs / "ingest.crt", certs / "ingest.key"
    if not (ca.exists() and crt.exists() and key.exists()):
        return fail(f"no certificates in {certs}: run infra/make-certs.sh first")

    source = MqttTelemetrySource(
        host=env.get("PSYCHRON_MQTT_HOST", "127.0.0.1"),
        port=int(env.get("PSYCHRON_MQTT_PORT", "8883")),
        ca_cert=str(ca),
        client_cert=str(crt),
        client_key=str(key),
    )

    def on_message(topic, payload, received_at):
        ingestor.handle(topic, payload, received_at)
        s = ingestor.stats
        if (s.stored + s.samples + s.duplicates + s.rejected) % 20 == 0:
            logging.info("stored=%d samples=%d duplicates=%d rejected=%d boots=%d",
                         s.stored, s.samples, s.duplicates, s.rejected, s.boots)

    try:
        source.run(on_message)
    except KeyboardInterrupt:
        source.stop()
        logging.info("stopped: stored=%d duplicates=%d rejected=%d",
                     ingestor.stats.stored, ingestor.stats.duplicates, ingestor.stats.rejected)
    finally:
        repo.close()
    return 0


def cmd_status(args) -> int:
    repo = PostgresReadingRepository(dsn_from(load_env()))
    try:
        print(render_status(repo, args.device))
    finally:
        repo.close()
    return 0


def cmd_watch(args) -> int:
    repo = PostgresReadingRepository(dsn_from(load_env()))
    try:
        while True:
            # Redraw in place rather than scrolling, so the numbers stay where
            # the eye left them.
            sys.stdout.write("\033[H\033[J")
            sys.stdout.write(render_status(repo, args.device))
            sys.stdout.write(f"\n\n  refreshing every {args.interval}s, Ctrl-C to stop\n")
            sys.stdout.flush()
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print()
    finally:
        repo.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="psychron")
    parser.add_argument("--device", default="esp32-01")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("run", help="consume MQTT and store readings").set_defaults(func=cmd_run)
    sub.add_parser("status", help="one-shot view of what has landed").set_defaults(func=cmd_status)

    watch = sub.add_parser("watch", help="live view of what is landing")
    watch.add_argument("--interval", type=float, default=2.0)
    watch.set_defaults(func=cmd_watch)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
