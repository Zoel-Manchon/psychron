"""Publish a compiled firmware image so nodes can pull it.

The manifest is generated from the image, never written by hand: a digest typed
by a person is a digest that eventually does not match, and the failure lands on
the device as a refused update with no obvious cause.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIRMWARE_DIR = ROOT / "infra" / "firmware"
SKETCH = ROOT / "firmware" / "psychron_node"


def firmware_version() -> str | None:
    """Read FW_VERSION out of config.h so the manifest cannot disagree with it."""
    text = (SKETCH / "config.h").read_text(encoding="utf-8")
    m = re.search(r'#define\s+FW_VERSION\s+"([^"]+)"', text)
    return m.group(1) if m else None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="psychron-release")
    ap.add_argument("image", type=Path, help="the compiled .bin")
    ap.add_argument("--version", help="defaults to FW_VERSION in config.h")
    ap.add_argument("--dir", type=Path, default=FIRMWARE_DIR)
    args = ap.parse_args(argv)

    if not args.image.exists():
        return fail(f"no such image: {args.image}")

    version = args.version or firmware_version()
    if not version:
        return fail("could not read FW_VERSION from config.h; pass --version")

    payload = args.image.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    name = f"psychron_node-{version}.bin"

    args.dir.mkdir(parents=True, exist_ok=True)
    (args.dir / name).write_bytes(payload)

    manifest = {"version": version, "image": f"/{name}",
                "sha256": digest, "size": len(payload)}
    (args.dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n",
                                            encoding="utf-8")

    print(f"published {name}")
    print(f"  version  {version}")
    print(f"  size     {len(payload):,} bytes")
    print(f"  sha256   {digest}")
    print()
    print("A node still running this version will see it as up to date. Bump")
    print("FW_VERSION in config.h before building the image you want deployed.")
    return 0


def fail(msg: str) -> int:
    print(msg, file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
