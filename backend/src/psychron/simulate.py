"""A stand-in for the phone node: contract v2 over mTLS, with the real certificate.

Not a mock of the backend — this goes through the broker, the ACL and ingestion
exactly as the Android app will, so it proves the whole path on a machine with no
phone attached. It is also what someone cloning the repository can run to see the
phone panel fill without owning the hardware.

    python -m psychron.simulate                 # live, one window every 2 s
    python -m psychron.simulate --backfill 6    # first write 6 hours of history
"""

from __future__ import annotations

import argparse
import json
import math
import random
import secrets
import ssl
import time
from pathlib import Path

import paho.mqtt.client as mqtt

from .cli import DEFAULT_ENV, load_env
from .domain.telemetry import Q_REPLAYED

FIRMWARE = "sim-0.1.0"


class Room:
    """Plausible physics, not noise. A barometric trace that wanders like the
    atmosphere, light that follows the hour, a quiet room with occasional events."""

    def __init__(self, seed: int) -> None:
        self.rng = random.Random(seed)
        self.pressure = 1013.2
        self.heading = self.rng.uniform(0, 360)
        self.battery = 30.5

    def window(self, epoch: float, win_s: float) -> dict:
        r = self.rng
        hour = (epoch / 3600.0) % 24

        # A slow random walk around a front passing through: ~1 hPa over hours.
        self.pressure += r.gauss(0, 0.004 * win_s) - 0.0002 * (self.pressure - 1013.2)
        pressure = self.pressure + 0.35 * math.sin(2 * math.pi * hour / 12)

        daylight = max(0.0, math.sin(math.pi * (hour - 7) / 13))
        lux = max(0.0, 12 + 480 * daylight + r.gauss(0, 6))

        event = r.random() < 0.04
        rms = -52 + r.gauss(0, 2) + (22 if event else 0)
        peak = min(0.0, rms + 14 + abs(r.gauss(0, 3)))

        moved = r.random() < 0.03
        accel_rms = abs(r.gauss(0.03, 0.01)) + (1.4 if moved else 0)
        gyro_rms = abs(r.gauss(0.008, 0.003)) + (0.9 if moved else 0)
        if moved:
            self.heading = (self.heading + r.uniform(-40, 40)) % 360

        self.battery += r.gauss(0, 0.02) - 0.01 * (self.battery - 30.5)

        return {
            "baro": {"hpa": round(pressure, 2)},
            "light": {"lux": round(lux, 1)},
            "sound": {"rms_dbfs": round(max(-160, rms), 1), "peak_dbfs": round(max(-160, peak), 1)},
            "accel": {"rms": round(accel_rms, 3), "peak": round(accel_rms * 2.6, 3)},
            "gyro": {"rms": round(gyro_rms, 3), "peak": round(gyro_rms * 2.4, 3)},
            "mag": {"ut": round(42 + r.gauss(0, 0.6), 1),
                    "heading": round(self.heading % 360, 1)},
            "batt": {"c": round(self.battery, 1)},
        }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="psychron-simulate")
    ap.add_argument("--device", default="phone-01")
    ap.add_argument("--window", type=float, default=2.0, help="seconds per window")
    ap.add_argument("--backfill", type=float, default=0.0,
                    help="hours of history to publish before going live")
    ap.add_argument("--count", type=int, default=0, help="stop after N live windows (0 = forever)")
    args = ap.parse_args(argv)

    env = load_env()
    certs = Path(env.get("PSYCHRON_CERT_DIR", str(DEFAULT_ENV.parent / "certs")))
    host = env.get("PSYCHRON_MQTT_HOST", "127.0.0.1")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                         client_id=f"{args.device}-sim-{secrets.token_hex(3)}")
    client.tls_set(ca_certs=str(certs / "ca.crt"),
                   certfile=str(certs / f"{args.device}.crt"),
                   keyfile=str(certs / f"{args.device}.key"),
                   tls_version=ssl.PROTOCOL_TLS_CLIENT)
    client.connect(host, int(env.get("PSYCHRON_MQTT_PORT", "8883")), keepalive=30)
    client.loop_start()

    topic = f"psychron/v2/{args.device}/sample"
    boot = secrets.randbits(32)
    room = Room(boot)
    win_ms = int(args.window * 1000)
    start = time.time()
    seq = 0

    def publish(end_epoch: float, number: int, replayed: bool = False) -> None:
        msg = {
            "v": 2, "dev": args.device, "fw": FIRMWARE, "boot": boot, "seq": number,
            # A replayed window carries no clock of its own: it is placed by the
            # boot anchor, which is the mechanism this exists to exercise.
            "ts": None if replayed else int(end_epoch),
            "up": int((end_epoch - start) * 1000),
            "win": win_ms, "q": Q_REPLAYED if replayed else 0,
            **room.window(end_epoch, args.window),
        }
        client.publish(topic, json.dumps(msg), qos=1).wait_for_publish(5)

    if args.backfill > 0:
        # Behaves like a phone that was offline for the whole period. The order
        # matters and is the point: one live window first, with a clock close to
        # arrival, so ingestion anchors the boot; then the backlog, clockless and
        # marked replayed. Sent the other way round — or with its old timestamps —
        # every historical window would be stamped with its arrival time and the
        # hours would collapse onto a single instant.
        windows = int(args.backfill * 3600 / args.window)
        start = time.time() - windows * args.window
        publish(time.time(), windows + 1)
        for i in range(windows):
            publish(start + (i + 1) * args.window, i + 1, replayed=True)
        seq = windows + 1
        print(f"backfilled {windows} windows ({args.backfill} h) via the boot anchor")

    print(f"publishing to {topic} every {args.window} s as {args.device} over mTLS")
    sent = 0
    try:
        while args.count == 0 or sent < args.count:
            time.sleep(args.window)
            seq += 1
            publish(time.time(), seq)
            sent += 1
    except KeyboardInterrupt:
        pass
    finally:
        client.loop_stop()
        client.disconnect()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
