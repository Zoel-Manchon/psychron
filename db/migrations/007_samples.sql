-- Contract v2: window summaries from a multi-sensor node.
--
-- A table of its own rather than more columns on `reading`. The two carry
-- different things at different rates — one instantaneous temperature and
-- humidity every 3 s, against a two-second summary of seven sensors — and folding
-- them together would give every ESP32 row eleven permanent nulls and every phone
-- row two, with the CHECK constraints of each kind applying to rows of the other.
--
-- The envelope columns are deliberately identical to `reading`'s, so the
-- timestamp resolution, the identity rule and the gap queries read the same way.

CREATE TABLE sample (
    time             timestamptz      NOT NULL,
    device_id        text             NOT NULL REFERENCES device (device_id),

    boot_id          bigint           NOT NULL,
    seq              bigint           NOT NULL,

    device_time      timestamptz,
    received_at      timestamptz      NOT NULL,
    uptime_ms        bigint           NOT NULL,
    window_ms        integer          NOT NULL,

    quality          integer          NOT NULL DEFAULT 0,
    firmware         text             NOT NULL,

    -- Null means the node took no sample of that quantity in the window. The
    -- ranges repeat the contract's, so a validation bug upstream still cannot
    -- write a physically absurd value.
    pressure_hpa     double precision CHECK (pressure_hpa    BETWEEN 300 AND 1100),
    illuminance_lux  double precision CHECK (illuminance_lux BETWEEN 0 AND 200000),
    sound_rms_dbfs   double precision CHECK (sound_rms_dbfs  BETWEEN -160 AND 0),
    sound_peak_dbfs  double precision CHECK (sound_peak_dbfs BETWEEN -160 AND 0),
    accel_rms        double precision CHECK (accel_rms       BETWEEN 0 AND 160),
    accel_peak       double precision CHECK (accel_peak      BETWEEN 0 AND 160),
    gyro_rms         double precision CHECK (gyro_rms        BETWEEN 0 AND 40),
    gyro_peak        double precision CHECK (gyro_peak       BETWEEN 0 AND 40),
    magnetic_ut      double precision CHECK (magnetic_ut     BETWEEN 0 AND 2000),
    heading_deg      double precision CHECK (heading_deg >= 0 AND heading_deg < 360),
    battery_temp_c   double precision CHECK (battery_temp_c  BETWEEN -40 AND 100),

    CONSTRAINT sample_window_sane CHECK (window_ms BETWEEN 100 AND 60000)
);

SELECT create_hypertable('sample', 'time');

-- The same identity lookup `reading` has: ingestion checks (device, boot, seq)
-- before inserting, because the unique index on a hypertable must include the
-- partitioning column and so cannot express that rule by itself.
CREATE INDEX sample_identity_lookup ON sample (device_id, boot_id, seq);
CREATE UNIQUE INDEX sample_identity ON sample (device_id, boot_id, seq, time);
CREATE INDEX sample_device_time ON sample (device_id, time DESC);
