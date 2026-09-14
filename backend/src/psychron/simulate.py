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
from .domain.weather import station_pressure

FIRMWARE = "sim-0.2.0"


class Room:
    """Plausible physics, not noise. A barometric trace that wanders like the
    atmosphere, light that follows the hour, a quiet room with occasional events."""

    # A public square, not anyone's address: the simulated walk starts here.
    ORIGIN = (40.416775, -3.703790)
    ALTITUDE_M = 657.0

    def __init__(self, seed: int) -> None:
        self.rng = random.Random(seed)
        # Station pressure at the walk's altitude, so that reducing it to sea
        # level lands on an ordinary day rather than a record high.
        self.base_hpa = station_pressure(1013.2, self.ALTITUDE_M)
        self.pressure = self.base_hpa
        self.heading = self.rng.uniform(0, 360)
        self.battery = 30.5
        self.east_m = self.north_m = 0.0
        self.l_history: list[float] = []
        self.event_pending: dict | None = None

    def window(self, epoch: float, win_s: float) -> dict:
        r = self.rng
        hour = (epoch / 3600.0) % 24

        # A slow random walk around a front passing through: ~1 hPa over hours.
        self.pressure += r.gauss(0, 0.004 * win_s) - 0.0002 * (self.pressure - self.base_hpa)
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

        # A slow walk: 1.2 m/s along the heading, turning now and then. Station
        # pressure follows the height of the ground, ~0.12 hPa per metre.
        speed = abs(r.gauss(1.2, 0.2))
        self.east_m += speed * win_s * math.sin(math.radians(self.heading))
        self.north_m += speed * win_s * math.cos(math.radians(self.heading))
        ground_m = 3.0 * math.sin(self.east_m / 40.0)
        pressure -= 0.12 * ground_m
        lat = self.ORIGIN[0] + self.north_m / 111_320.0
        lon = self.ORIGIN[1] + self.east_m / (111_320.0 * math.cos(math.radians(self.ORIGIN[0])))

        # A-weighting takes a few dB off a room's low-frequency hum.
        laeq = max(-160.0, rms - 3.0)
        self.l_history = (self.l_history + [laeq])[-30:]
        ordered = sorted(self.l_history)
        noise = {"laeq": round(laeq, 1), "lamax": round(min(0.0, laeq + 6 + abs(r.gauss(0, 2))), 1)}
        if len(ordered) >= 15:
            noise["l10"] = round(ordered[int(0.9 * (len(ordered) - 1))], 1)
            noise["l90"] = round(ordered[int(0.1 * (len(ordered) - 1))], 1)

        # Signal falls off with distance from a mast 300 m east of the start.
        mast_m = math.hypot(self.east_m - 300.0, self.north_m)
        rsrp = max(-140.0, min(-44.0, -70.0 - 22.0 * math.log10(max(mast_m, 10.0) / 10.0) + r.gauss(0, 2)))

        if event:
            self.event_pending = {"kind": "vibration", "dur": int(r.uniform(300, 2500)),
                                  "pga": round(abs(r.gauss(0.25, 0.1)) + 0.05, 3),
                                  "ratio": round(r.uniform(4.2, 12.0), 1),
                                  "freq": round(r.uniform(4.0, 30.0), 1)}

        return {
            "baro": {"hpa": round(pressure, 2)},
            "light": {"lux": round(lux, 1)},
            "sound": {"rms_dbfs": round(max(-160, rms), 1), "peak_dbfs": round(max(-160, peak), 1)},
            "accel": {"rms": round(accel_rms, 3), "peak": round(accel_rms * 2.6, 3)},
            "gyro": {"rms": round(gyro_rms, 3), "peak": round(gyro_rms * 2.4, 3)},
            "mag": {"ut": round(42 + r.gauss(0, 0.6), 1),
                    "heading": round(self.heading % 360, 1)},
            "batt": {"c": round(self.battery, 1)},
            "loc": {"lat": round(lat, 6), "lon": round(lon, 6), "acc": round(abs(r.gauss(4, 1)) + 1, 1),
                    "alt": round(self.ALTITUDE_M + ground_m + r.gauss(0, 1.5), 1),
                    "alt_acc": round(abs(r.gauss(4, 1)) + 2, 1), "spd": round(speed, 2)},
            "noise": noise,
            "cell": {"rat": "nr" if rsrp > -105 else "lte", "rsrp": round(rsrp, 1),
                     "rsrq": round(max(-43.0, min(20.0, -9.0 + (rsrp + 90) / 8 + r.gauss(0, 1))), 1),
                     "sinr": round(max(-23.0, min(40.0, 18.0 + (rsrp + 90) / 3 + r.gauss(0, 2))), 1),
                     "band": 78 if rsrp > -105 else 20},
            "net": {"via": "cell", "vpn": True, "rtt": round(abs(r.gauss(65, 15)) + 20, 1)},
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
    event_topic = f"psychron/v2/{args.device}/event"
    boot = secrets.randbits(32)
    room = Room(boot)
    win_ms = int(args.window * 1000)
    start = time.time()
    seq = 0
    event_seq = 0

    def clock(epoch: float, replayed: bool) -> dict:
        # A replayed message carries no clock of its own: it is placed by the boot
        # anchor, which is the mechanism this exists to exercise.
        if replayed:
            return {"ts": None}
        return {"ts": int(epoch), "ms": int((epoch % 1) * 1000)}

    def publish(end_epoch: float, number: int, replayed: bool = False) -> None:
        nonlocal event_seq
        msg = {
            "v": 2, "dev": args.device, "fw": FIRMWARE, "boot": boot, "seq": number,
            **clock(end_epoch, replayed),
            "up": int((end_epoch - start) * 1000),
            "win": win_ms, "q": Q_REPLAYED if replayed else 0,
            **room.window(end_epoch, args.window),
        }
        client.publish(topic, json.dumps(msg), qos=1).wait_for_publish(5)
        if room.event_pending is not None:
            # Started somewhere inside the window just summarised.
            began = end_epoch - args.window * room.rng.random()
            event_seq += 1
            client.publish(event_topic, json.dumps({
                "v": 2, "dev": args.device, "fw": FIRMWARE, "boot": boot, "seq": event_seq,
                **clock(began, replayed), "up": int((began - start) * 1000),
                "q": Q_REPLAYED if replayed else 0, **room.event_pending,
            }), qos=1).wait_for_publish(5)
            room.event_pending = None

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
