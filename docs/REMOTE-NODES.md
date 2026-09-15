# Nodes away from the LAN

The broker listens on this host's LAN address. A phone on mobile data cannot reach
a private address, so it needs a second one that can be reached from anywhere.
The node takes both and picks per network; the broker certificate names both.

| | |
|---|---|
| **Recommended path** | [Tailscale](https://tailscale.com) (WireGuard) on the host and the phone. No port opened on the router, works behind carrier-grade NAT, and mutual TLS stays end to end: the tunnel carries the same TLS stream the LAN does |
| **Not recommended** | Port-forwarding 8883 on the router. mTLS makes it defensible, but it puts the broker on the internet for every scanner, and many home connections are behind CGNAT anyway |
| **Not possible as is** | Cloudflare Tunnel and similar HTTP edges: they terminate TLS, so the broker would no longer see the node's client certificate, which is its identity |

## Setting it up

1. Install Tailscale on this host and on the phone, signed in to the same tailnet.
   On the phone, enable *Always-on VPN* for Tailscale in Android's VPN settings so
   the tunnel is up whenever the node is.
2. Put the host's tailnet address in `infra/.env`:
   ```
   PSYCHRON_MQTT_REMOTE_HOST=100.x.y.z     # tailscale ip -4, or its MagicDNS name
   ```
3. Re-sign the server certificates and reload the services that hold them.
   Keys are kept, so the ESP32 and the phone need nothing new:
   ```
   cd infra && ./make-certs.sh && docker compose restart broker web fwserver
   ```
4. Re-provision the phone, which now receives `hosts=<LAN>,<tailnet>`:
   ```
   ./provision-phone.sh
   ```
5. If the phone cannot connect over the tunnel, check that Windows Firewall allows
   inbound 8883 on the Tailscale adapter's network profile. Docker Desktop's
   default rule covers the Public profile only.

## How the node chooses

- On Wi-Fi or Ethernet, the addresses are tried in provisioned order, LAN first.
- On mobile data, private addresses (10/8, 172.16/12, 192.168/16, `.local`) move
  to the end. 100.64/10, where Tailscale lives, is not private in this sense.
- The address that last worked is tried first until the network changes.
- A change of default network drops the connection at once and reconnects on the
  new one, instead of waiting for a keepalive to notice the old one went quiet.
  Losing Wi-Fi drops only a connection to a LAN address: one through the tunnel
  survives the handover underneath it.
- Windows held during the switch are sent afterwards, flagged as replayed, and
  placed by their own timestamps.

## What it costs

The screen shows this session's traffic split into metered and unmetered, and
a daily rate once there is a minute to extrapolate from. At one window every two
seconds, each is a message of a few hundred bytes plus MQTT, TLS and TCP framing
and the QoS 1 acknowledgement, so expect in the order of 30–40 MB a day with
location, noise and the cell included, more through a tunnel.

Battery matters more than bytes. A modem that transmits every two seconds never
leaves its connected state, which on LTE and NR is the expensive one. So on a
metered network the node sends in batches: messages wait in the on-disk outbox
until the oldest is 30 seconds old, then leave together, and the modem can idle
in between. The windows keep their own timestamps, so the record is unchanged;
the live panel simply lags by up to half a minute, and the phone's screen says
"batching every 30 s" while it does. Back on Wi-Fi, messages go out as measured.
