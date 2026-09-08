# Telemetry contract v1

The wire format between a node and ingestion. **This document and the schema are the
only parts of the system that cannot be fixed retroactively** — a backend can be
rewritten, but a month of readings recorded without the field you needed is gone.

## Message

One JSON object per MQTT message, published to `psychron/v1/<device_id>/reading`
every 3 s — a cadence chosen to clear the DHT library's own 2000 ms read cache,
which serves the previous measurement to any caller arriving even a millisecond
early.
JSON rather than a packed binary format: at this rate the bandwidth is irrelevant,
and being able to read the wire with `mosquitto_sub` during a fault is worth more
than the bytes.

```json
{
  "v":    1,
  "dev":  "esp32-01",
  "fw":   "1.2.0",
  "boot": 2748109371,
  "seq":  1234,
  "ts":   1789012345,
  "up":   2468000,
  "t":    23.42,
  "h":    51.20,
  "q":    0
}
```

| Field | Type | Meaning |
|---|---|---|
| `v` | int | Contract version. Ingestion rejects anything it does not know. |
| `dev` | string | Device identity. Under mTLS this must match the certificate SAN; the topic is never trusted for identity. |
| `fw` | string | Firmware version, so a bad release can be found in the data later. |
| `boot` | uint32 | Random id drawn once at startup. |
| `seq` | uint32 | Monotonic counter within a boot, from 1. |
| `ts` | int \| null | Device wall clock, epoch seconds. **Null when NTP has not synced.** |
| `up` | uint32 | Milliseconds since boot. Always valid. |
| `t` | float \| null | Temperature, °C. Null on a failed read. |
| `h` | float \| null | Relative humidity, %. Null on a failed read. |
| `q` | int | Quality bitfield, see below. |

## The three decisions that matter

**`ts` is nullable, and null is not an error.** A device whose NTP sync has not
completed does not know the time. Sending 1970, or the compile date, or the server's
guess, records a lie that no later analysis can detect. Null is a fact ingestion can
act on.

**`up` exists because `ts` cannot be trusted.** Uptime is monotonic from the moment
the chip starts and survives NTP failure, clock steps and DST. With `up` and the
server receive time, a reading's true instant can be reconstructed even when the
device clock was wrong — including for readings replayed hours late from the offline
buffer. This is the field that will save the dataset, and it costs four bytes.

**`boot` plus `seq` is the identity of a reading.** `seq` alone is useless because it
restarts at 1 after every reboot, so gap detection would see a reboot as a 1200-reading
loss and a duplicate as legitimate. `(dev, boot, seq)` is unique for the life of the
system, which makes ingestion idempotent: a redelivered MQTT message is recognised and
dropped rather than double-counted.

## Quality bitfield

| Bit | Value | Meaning |
|---|---|---|
| 0 | 1 | Clock not NTP-synced when the reading was taken; `ts` is null |
| 1 | 2 | Replayed from the offline buffer, not sent live |
| 2 | 4 | The preceding read returned nothing; failures cluster, so this marks the edge of one |
| 3 | 8 | Value outside the DHT22 datasheet range |
| 4 | 16 | Reading interval deviated from the nominal period |

Flags are recorded, never used to silently discard. A reading marked bit 3 is still
stored — the analysis decides what to do with it, not the transport.

## Timestamp resolution

Ingestion writes three columns and never collapses them:

- `device_time` — what the device claimed, null if it did not know.
- `received_at` — when the server took the message off the broker.
- `time` — the authoritative instant, resolved as: `device_time` when the clock was
  synced and within a tolerance of `received_at`; otherwise reconstructed from
  `received_at` minus the transport delay implied by `up`.

Keeping all three means the resolution can be recomputed later if the rule turns out
to be wrong. Collapsing them at ingestion cannot be undone.

## Rejection

A message that fails validation is written to `rejected_message` with the raw payload
and the reason. Nothing is dropped silently — a rejection rate is a health signal, and
a bug in validation is only recoverable if the originals were kept.
