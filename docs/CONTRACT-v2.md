# Telemetry contract v2

v2 exists because a second kind of node exists. v1 is frozen and still spoken by
the ESP32; nothing in it changes. See [CONTRACT.md](CONTRACT.md) for the fields the
two versions share and the reasoning behind them — that document still applies
wherever this one is silent.

## What v2 changes, and what it deliberately does not

**Unchanged:** the envelope. `dev`, `fw`, `boot`, `seq`, `ts`, `up` and `q` mean
exactly what they mean in v1, and the timestamp resolution is the same rule applied
by the same code. `(dev, boot, seq)` is still the identity of a message.

**Changed:** what a message carries. v1 is one instantaneous reading of one sensor.
v2 is a **window summary** across several sensors, each one optional.

The reason is rate. An accelerometer delivers hundreds of samples a second; shipping
them raw would turn a room station into a vibration logger writing tens of millions
of rows a day, none of which answers a question anyone is asking. A node summarises a
window on the device and sends the summary. The raw samples never leave it.

## Message

Published to `psychron/v2/<device_id>/sample` once per window.

```json
{
  "v":     2,
  "dev":   "phone-01",
  "fw":    "0.1.0",
  "boot":  3141592653,
  "seq":   42,
  "ts":    1789012345,
  "ms":    437,
  "up":    84000,
  "win":   2000,
  "q":     0,
  "baro":  { "hpa": 1013.42 },
  "light": { "lux": 312.0 },
  "sound": { "rms_dbfs": -48.2, "peak_dbfs": -31.0 },
  "accel": { "rms": 0.042, "peak": 0.310 },
  "gyro":  { "rms": 0.011, "peak": 0.090 },
  "mag":   { "ut": 41.2, "heading": 212.5 },
  "batt":  { "c": 31.4 },
  "loc":   { "lat": 40.416775, "lon": -3.70379, "acc": 4.5, "alt": 657.2, "alt_acc": 3.1, "spd": 1.2 },
  "noise": { "laeq": -52.4, "lamax": -41.0, "l10": -47.9, "l90": -58.3 },
  "cell":  { "rat": "nr", "rsrp": -97.0, "rsrq": -11.0, "sinr": 8.5, "band": 78 },
  "net":   { "via": "cell", "vpn": true, "rtt": 84.0 }
}
```

### Envelope

| Field | Type | Meaning |
|---|---|---|
| `v` | int | `2`. |
| `dev`, `fw`, `boot`, `seq`, `ts`, `q` | | As in v1. |
| `ms` | int, optional | Milliseconds within the `ts` second, 0–999, as in v1's revision. Only with a non-null `ts`. |
| `up` | int | Milliseconds since `boot` was drawn. **Widened to 2⁵³−1** — v1's 32 bits wrap after 49.7 days, which is an ESP32 limit, not a law. |
| `win` | int | Length of the window this message summarises, 100–60000 ms. `ts` and `up` mark its **end**. |

### Measurement groups

Every group is optional. **An absent group means no sample was taken in this
window** — the sensor does not exist, was not granted, or produced nothing. There is
one way to say "no data", not two: `null` inside a group is a validation error.

Inside a present group, every field marked *required* is present. A field marked
*optional* is either absent or valid — a GNSS fix without an altitude is still a fix,
and a cell without a SINR report is still a cell.

| Group | Field | Unit | Range | | Definition |
|---|---|---|---|---|---|
| `baro` | `hpa` | hPa | 300–1100 | required | Mean station pressure over the window |
| `light` | `lux` | lx | 0–200000 | required | Mean illuminance |
| `sound` | `rms_dbfs` | dBFS | −160–0 | required | RMS level of the window, relative to full scale |
| | `peak_dbfs` | dBFS | −160–0 | required | Highest absolute sample |
| `accel` | `rms` | m/s² | 0–160 | required | RMS of linear acceleration magnitude, **gravity removed** |
| | `peak` | m/s² | 0–160 | required | Highest magnitude |
| `gyro` | `rms` | rad/s | 0–40 | required | RMS of angular speed magnitude |
| | `peak` | rad/s | 0–40 | required | Highest magnitude |
| `mag` | `ut` | µT | 0–2000 | required | Mean magnetic field magnitude |
| | `heading` | ° | 0 ≤ h < 360 | required | Azimuth from magnetic north at the end of the window |
| `batt` | `c` | °C | −40–100 | required | Battery temperature |
| `loc` | `lat`, `lon` | ° | ±90, ±180 | required | Latest GNSS fix in the window, WGS84 |
| | `acc` | m | 0–10000 | required | Horizontal accuracy of that fix, one standard deviation |
| | `alt` | m | −500–9000 | optional | Altitude above **mean sea level**, not the WGS84 ellipsoid |
| | `alt_acc` | m | 0–10000 | optional | Vertical accuracy; only together with `alt` |
| | `spd` | m/s | 0–350 | optional | Ground speed |
| `noise` | `laeq` | dBFS(A) | −160–0 | required | A-weighted equivalent level over the window |
| | `lamax` | dBFS(A) | −160–0 | required | Loudest 125 ms A-weighted level in the window |
| | `l10` | dBFS(A) | −160–0 | optional | Level exceeded 10 % of the trailing minute |
| | `l90` | dBFS(A) | −160–0 | optional | Level exceeded 90 % of the trailing minute — the background |
| `cell` | `rat` | `lte` \| `nr` | | required | Radio technology of the serving cell |
| | `rsrp` | dBm | −156–−31 | required | Reference signal received power |
| | `rsrq` | dB | −43–20 | required | Reference signal received quality |
| | `sinr` | dB | −23–40 | optional | Signal to interference and noise |
| | `band` | int | 1–1024 | optional | 3GPP band number |
| `net` | `via` | `wifi` \| `cell` \| `ethernet` \| `other` | | required | Transport under the node's default network |
| | `vpn` | bool | | required | Whether that network is a VPN, as when tunnelled home |
| | `rtt` | ms | 0–60000 | optional | Mean publish-to-acknowledgement time over the previous window |

An unknown group is rejected, as is an unknown field inside a known group. A typo
that silently vanished would record a month of nothing under a name nobody queries.

## Events

Published to `psychron/v2/<device_id>/event` when something happens, rather than
on a schedule. The envelope is a sample's without `win`, and `ts` and `up` mark the
**start** of the event. Events are numbered with their own `seq`, so
`(dev, boot, seq)` identifies an event among events.

```json
{
  "v": 2, "dev": "phone-01", "fw": "android-0.5.0", "boot": 2718281828, "seq": 7,
  "ts": 1789413255, "ms": 250, "up": 3600000, "q": 0,
  "kind": "vibration", "dur": 1840, "pga": 0.412, "ratio": 6.3, "freq": 11.5
}
```

| Kind | Field | Unit | Range | | Definition |
|---|---|---|---|---|---|
| `vibration` | `dur` | ms | 1–600000 | required | From trigger to detrigger |
| | `pga` | m/s² | 0–160 | required | Peak acceleration during the event, gravity removed |
| | `ratio` | | 1–1000 | required | Highest short-term over long-term average — how far above the background it stood |
| | `freq` | Hz | 0–100 | optional | Dominant frequency, from zero crossings of the strongest axis |

## Alerts

The one message that flows from the server to the nodes. Retained, so a node that
connects learns the current state without having been there when it changed.

Published to `psychron/alerts/<kind>/<device_id>`:

```json
{ "kind": "battery_hot", "device": "phone-01", "state": "raised",
  "at": "2026-09-15T04:00:00Z", "value": 43.5, "threshold": 42.0,
  "message": "Phone battery at 43.5 °C" }
```

`state` is `raised` or `cleared`. Kinds: `pressure_falling`, `device_offline`,
`battery_hot`. Only the ingestion service may publish here, and only the phone may
subscribe.

## Decisions worth recording

**Gravity is removed from `accel`.** Raw acceleration magnitude sits near 9.81 m/s²
whatever the device is doing, so its RMS measures the planet. What is worth storing
is movement, which is what remains once gravity is taken out.

**Sound is dBFS, never dB SPL.** A phone microphone is not calibrated, and its gain
changes with the model, the case and the software. dBFS is honest about being
relative: comparable across time on one device, not across devices, and not a
measurement of loudness in the physical sense. A field named `db_spl` would be a lie
the schema could not detect. `noise` is A-weighted, which makes the number follow
the ear, not the physics: it is still relative to full scale. The panel adds a
calibration offset only when one has been measured against a sound level meter
and configured, and labels the result as calibrated.

**Audio never leaves the device and is never stored on it.** The node reads PCM into
memory, reduces each window to a handful of numbers, and discards the buffer. There
is no field in this contract that could carry audio, by construction rather than by
policy.

**Battery temperature is the device, not the room.** It follows the charger, the
screen and the workload far more than the air around it. It is recorded because it
explains the other readings — a hot phone drifts — and must never be shown as room
temperature.

**Altitude is above mean sea level.** GNSS reports height over the WGS84 ellipsoid,
which differs from sea level by the local geoid, about 50 m over Spain. Pressure
reduced to sea level with the wrong one is off by 6 hPa, which is the difference
between two different forecasts.

**Location is precise, and stays private.** The node sends full-precision
coordinates because coverage and height are only worth mapping at that precision.
The API serves them only to an authenticated session, and the panel draws the track
relative to its own start, without a base map, so a screenshot shows a shape rather
than an address.

## Quality bits

v1's bits keep their meaning. v2 adds none; ingestion's `0x0100` and `0x0200`
apply to both versions because the timestamp rule is shared.
