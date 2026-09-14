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
  "up":    84000,
  "win":   2000,
  "q":     0,
  "baro":  { "hpa": 1013.42 },
  "light": { "lux": 312.0 },
  "sound": { "rms_dbfs": -48.2, "peak_dbfs": -31.0 },
  "accel": { "rms": 0.042, "peak": 0.310 },
  "gyro":  { "rms": 0.011, "peak": 0.090 },
  "mag":   { "ut": 41.2, "heading": 212.5 },
  "batt":  { "c": 31.4 }
}
```

### Envelope

| Field | Type | Meaning |
|---|---|---|
| `v` | int | `2`. |
| `dev`, `fw`, `boot`, `seq`, `ts`, `q` | | As in v1. |
| `up` | int | Milliseconds since `boot` was drawn. **Widened to 2⁵³−1** — v1's 32 bits wrap after 49.7 days, which is an ESP32 limit, not a law. |
| `win` | int | Length of the window this message summarises, 100–60000 ms. `ts` and `up` mark its **end**. |

### Measurement groups

Every group is optional. **An absent group means no sample was taken in this
window** — the sensor does not exist, was not granted, or produced nothing. There is
one way to say "no data", not two: a group that is present has every field filled
with a finite number, and `null` inside a group is a validation error.

| Group | Field | Unit | Range | Definition |
|---|---|---|---|---|
| `baro` | `hpa` | hPa | 300–1100 | Mean station pressure over the window |
| `light` | `lux` | lx | 0–200000 | Mean illuminance |
| `sound` | `rms_dbfs` | dBFS | −160–0 | RMS level of the window, relative to full scale |
| | `peak_dbfs` | dBFS | −160–0 | Highest absolute sample |
| `accel` | `rms` | m/s² | 0–160 | RMS of linear acceleration magnitude, **gravity removed** |
| | `peak` | m/s² | 0–160 | Highest magnitude |
| `gyro` | `rms` | rad/s | 0–40 | RMS of angular speed magnitude |
| | `peak` | rad/s | 0–40 | Highest magnitude |
| `mag` | `ut` | µT | 0–2000 | Mean magnetic field magnitude |
| | `heading` | ° | 0 ≤ h < 360 | Azimuth from magnetic north at the end of the window |
| `batt` | `c` | °C | −40–100 | Battery temperature |

An unknown group is rejected, as is an unknown field inside a known group. A typo
that silently vanished would record a month of nothing under a name nobody queries.

## Four decisions worth recording

**Gravity is removed from `accel`.** Raw acceleration magnitude sits near 9.81 m/s²
whatever the device is doing, so its RMS measures the planet. What is worth storing
is movement, which is what remains once gravity is taken out.

**Sound is dBFS, never dB SPL.** A phone microphone is not calibrated, and its gain
changes with the model, the case and the software. dBFS is honest about being
relative: comparable across time on one device, not across devices, and not a
measurement of loudness in the physical sense. A field named `db_spl` would be a lie
the schema could not detect.

**Audio never leaves the device and is never stored on it.** The node reads PCM into
memory, reduces each window to two numbers, and discards the buffer. There is no field
in this contract that could carry audio, by construction rather than by policy.

**Battery temperature is the device, not the room.** It follows the charger, the
screen and the workload far more than the air around it. It is recorded because it
explains the other readings — a hot phone drifts — and must never be shown as room
temperature.

## Quality bits

v1's bits keep their meaning. v2 adds none yet; ingestion's `0x0100` and `0x0200`
apply to both versions because the timestamp rule is shared.
