"""Send the node its TLS material over the serial port.

The private key never goes into the firmware image, into git, or through the
broker: it is handed to the device directly over the cable, once, and lives in
NVS from then on.

This is the interim path. Keystone already issues and revokes certificates from
its own CA with single-use enrolment tokens, and replacing this script with a
real enrolment against it is the natural next step — but a device has to be able
to speak TLS before it can enrol over TLS, so something has to seed the first
credential over a channel that needs no credentials of its own.
"""

from __future__ import annotations

import argparse
import base64
import sys
import time
from pathlib import Path

try:
    import serial
    from serial.tools import list_ports
except ImportError:  # pragma: no cover
    sys.exit("pyserial is missing. Install it with: pip install pyserial")

BAUD = 115200
# Short enough that no plausible receive buffer can be overrun, and the
# acknowledgement per chunk keeps the host from getting ahead regardless.
CHUNK = 180
SLOTS = {"ca": "ca.crt", "crt": "esp32-01.crt", "key": "esp32-01.key"}


def open_port(name: str) -> serial.Serial:
    ser = serial.Serial()
    ser.port = name
    ser.baudrate = BAUD
    ser.timeout = 2.0
    # DTR drives GPIO0 and RTS drives EN; both low keeps the board out of the
    # bootloader while still letting RTS be pulsed for a plain reset.
    ser.dtr = False
    ser.rts = False
    ser.open()

    # Reset, then work in the window before the node reaches the network. A node
    # holding credentials it cannot use spends most of its time blocked inside a
    # failing TLS handshake and answers the serial port only in the gaps, which
    # is what made provisioning time out. Straight after boot it is entirely
    # ours, and it announces itself so there is no need to guess how long to wait.
    ser.rts = True
    time.sleep(0.15)
    ser.reset_input_buffer()
    ser.rts = False

    deadline = time.time() + 15
    while time.time() < deadline:
        line = ser.readline().decode("utf-8", errors="replace").strip()
        if line.startswith("PROVISION;"):
            return ser
    raise serial.SerialException("the node never announced itself after reset")


def send(ser: serial.Serial, line: str, expect: str = "OK",
         timeout: float = 25.0) -> tuple[bool, str]:
    ser.reset_input_buffer()
    ser.write((line + "\n").encode())
    ser.flush()

    # Generous, because a node holding invalid credentials blocks its loop in
    # failing TLS handshakes and only services the serial port between them.
    deadline = time.time() + timeout
    while time.time() < deadline:
        raw = ser.readline().decode("utf-8", errors="replace").strip()
        if not raw:
            continue
        # The node keeps sampling and logging while it waits to be provisioned,
        # so its telemetry lines are interleaved with the replies.
        if raw.startswith(("OK", "ERR", "PROVISION")):
            return raw.startswith(expect), raw
    return False, "timed out waiting for a reply"


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parents[3]
    ap = argparse.ArgumentParser(prog="psychron-provision")
    ap.add_argument("--port", help="serial port, e.g. COM4 (default: auto-detect)")
    ap.add_argument("--certs", type=Path, default=here / "infra" / "certs")
    ap.add_argument("--device", default="esp32-01")
    ap.add_argument("--erase", action="store_true", help="clear the stored material first")
    args = ap.parse_args(argv)

    slots = dict(SLOTS)
    slots["crt"] = f"{args.device}.crt"
    slots["key"] = f"{args.device}.key"

    missing = [f for f in slots.values() if not (args.certs / f).exists()]
    if missing:
        return fail(f"missing certificate files in {args.certs}: {', '.join(missing)}")

    port = args.port
    if not port:
        ports = list(list_ports.comports())
        if not ports:
            return fail("no serial ports found")
        port = ports[0].device
        print(f"Auto-detected {port}")

    try:
        ser = open_port(port)
    except serial.SerialException as exc:
        return fail(f"could not open {port}: {exc}\nClose any serial monitor and retry.")

    with ser:
        print(f"Provisioning {args.device} on {port}\n")
        if args.erase:
            ok, reply = send(ser, "ERASE")
            print(f"  erase                {reply}")

        for slot, filename in slots.items():
            pem = (args.certs / filename).read_bytes()
            encoded = base64.b64encode(pem).decode()

            ok, reply = send(ser, f"BEGIN {slot}")
            if not ok:
                return fail(f"could not start {slot}: {reply}")

            # Each chunk is acknowledged before the next is sent. That reply is
            # the flow control: without it the host outruns the device's receive
            # buffer and the tail of a certificate is silently lost, which shows
            # up later as a credential that decodes to the wrong length.
            for i in range(0, len(encoded), CHUNK):
                ok, reply = send(ser, f"C {encoded[i:i + CHUNK]}")
                if not ok:
                    return fail(f"chunk {i // CHUNK} of {slot} rejected: {reply}")

            ok, reply = send(ser, "END")
            status = "OK  " if ok else "FAIL"
            print(f"  [{status}] {slot:<4} {filename:<18} {len(pem):>5} bytes   {reply}")

            # The device echoes what it decoded. Comparing it against the file on
            # disk is the whole point: a transfer that arrives short still looks
            # like a success from this end otherwise.
            if ok and f" {len(pem)}" not in reply:
                return fail(f"{slot} arrived truncated: sent {len(pem)} bytes, node reports '{reply}'")
            if not ok:
                return fail("provisioning stopped at the first failure; nothing partial is useful")

        ok, reply = send(ser, "STATUS", expect="PROVISION")
        print(f"\n  {reply}")
        if "complete=yes" not in reply:
            return fail("the node still reports itself incomplete")

    print("\nProvisioned. The node will connect over mTLS once NTP has synced.")
    return 0


def fail(msg: str) -> int:
    print(msg, file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
