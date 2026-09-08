-- Psychron schema v1. See docs/CONTRACT.md for why the timestamp columns are split.

CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE device (
    device_id     text PRIMARY KEY,
    label         text        NOT NULL,
    sensor_model  text        NOT NULL,
    location      text,
    enrolled_at   timestamptz NOT NULL DEFAULT now(),
    -- Certificate fingerprint once mTLS is in place; null while the pipe is plain.
    cert_sha256   text,
    retired_at    timestamptz
);

CREATE TABLE reading (
    -- Authoritative instant, resolved at ingestion from the three sources below.
    time           timestamptz      NOT NULL,
    device_id      text             NOT NULL REFERENCES device (device_id),

    -- Identity of the reading. Unique for the life of the system, which is what
    -- makes ingestion idempotent under MQTT redelivery.
    boot_id        bigint           NOT NULL,
    seq            bigint           NOT NULL,

    -- The three timestamps are kept apart on purpose: collapsing them at write
    -- time cannot be undone, and the resolution rule may turn out to be wrong.
    device_time    timestamptz,
    received_at    timestamptz      NOT NULL,
    uptime_ms      bigint           NOT NULL,

    temperature_c  double precision,
    humidity_pct   double precision,
    quality        integer          NOT NULL DEFAULT 0,
    firmware       text             NOT NULL,

    CONSTRAINT reading_temp_sane CHECK (
        temperature_c IS NULL OR temperature_c BETWEEN -100 AND 150),
    CONSTRAINT reading_hum_sane CHECK (
        humidity_pct IS NULL OR humidity_pct BETWEEN -5 AND 105)
);

SELECT create_hypertable('reading', 'time');

-- Idempotency: a redelivered message collides here instead of double-counting.
-- The hypertable partitions on time, so it has to participate in the key.
CREATE UNIQUE INDEX reading_identity
    ON reading (device_id, boot_id, seq, time);

CREATE INDEX reading_device_time ON reading (device_id, time DESC);

-- Nothing is discarded silently. A rejection rate is a health signal, and a bug in
-- validation is only recoverable if the originals were kept.
CREATE TABLE rejected_message (
    id           bigserial PRIMARY KEY,
    received_at  timestamptz NOT NULL DEFAULT now(),
    topic        text        NOT NULL,
    payload      bytea       NOT NULL,
    reason       text        NOT NULL,
    device_id    text
);

CREATE INDEX rejected_recent ON rejected_message (received_at DESC);

-- Every boot the device announces itself. Reboots are events worth counting: they
-- explain sequence resets and often precede a hardware fault.
CREATE TABLE device_boot (
    device_id   text        NOT NULL REFERENCES device (device_id),
    boot_id     bigint      NOT NULL,
    first_seen  timestamptz NOT NULL DEFAULT now(),
    firmware    text        NOT NULL,
    reset_reason text,
    PRIMARY KEY (device_id, boot_id)
);
