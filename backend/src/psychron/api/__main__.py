"""Entry point: python -m psychron.api"""
from __future__ import annotations

import logging

import uvicorn

from ..cli import DEFAULT_ENV, dsn_from, load_env
from .app import create_app

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
env = load_env()

# Behind Caddy the panel is HTTPS only, so the session cookie must carry the
# Secure flag or the browser will happily send it over plain HTTP should the
# site ever become reachable that way. Off by default because development runs
# on loopback without TLS, and a Secure cookie there is simply never stored.
def _optional_float(name: str) -> float | None:
    raw = env.get(name, "").strip()
    return float(raw) if raw else None


# Both optional. PSYCHRON_STATION_ELEVATION_M is where the phone usually sits,
# used only when its own GNSS has no confident altitude; PSYCHRON_PHONE_SPL_OFFSET_DB
# is measured, never guessed: a reference sound level meter's dB(A) minus the
# phone's LAeq in dBFS(A), over the same steady noise.
app = create_app(dsn_from(env), DEFAULT_ENV,
                 station_elevation_m=_optional_float("PSYCHRON_STATION_ELEVATION_M"),
                 spl_offset_db=_optional_float("PSYCHRON_PHONE_SPL_OFFSET_DB"))

if __name__ == "__main__":
    # Bound to loopback: Caddy is the only public face.
    #
    # forwarded_allow_ips decides whose X-Forwarded-For is believed. Left at the
    # default, every request behind the proxy is recorded as coming from the
    # proxy, which makes "new location for this account" meaningless — every
    # sign-in looks like it came from the same place. Widening it to "*" would
    # be worse: anything that can reach the port could then claim any address.
    uvicorn.run(app,
                host=env.get("PSYCHRON_API_HOST", "127.0.0.1"),
                port=int(env.get("PSYCHRON_API_PORT", "8000")),
                proxy_headers=True,
                forwarded_allow_ips=env.get("PSYCHRON_TRUSTED_PROXIES", "127.0.0.1"))
