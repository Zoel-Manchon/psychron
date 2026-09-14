-- Contract v2, revision 2: location, weighted noise, the serving cell, the
-- network path; vibration events; and the alerts raised from all of it.
--
-- Columns on `sample` rather than side tables. They arrive in the same window as
-- everything else, are queried against the same time axis, and a join per chart
-- would buy nothing but a slower chart. As before, every range repeats the
-- contract's, so a validation bug upstream still cannot store an absurd value.

ALTER TABLE sample
    ADD COLUMN lat              double precision CHECK (lat BETWEEN -90 AND 90),
    ADD COLUMN lon              double precision CHECK (lon BETWEEN -180 AND 180),
    ADD COLUMN loc_acc_m        double precision CHECK (loc_acc_m BETWEEN 0 AND 10000),
    ADD COLUMN alt_msl_m        double precision CHECK (alt_msl_m BETWEEN -500 AND 9000),
    ADD COLUMN alt_acc_m        double precision CHECK (alt_acc_m BETWEEN 0 AND 10000),
    ADD COLUMN speed_ms         double precision CHECK (speed_ms BETWEEN 0 AND 350),
    ADD COLUMN noise_laeq_dbfs  double precision CHECK (noise_laeq_dbfs  BETWEEN -160 AND 0),
    ADD COLUMN noise_lamax_dbfs double precision CHECK (noise_lamax_dbfs BETWEEN -160 AND 0),
    ADD COLUMN noise_l10_dbfs   double precision CHECK (noise_l10_dbfs   BETWEEN -160 AND 0),
    ADD COLUMN noise_l90_dbfs   double precision CHECK (noise_l90_dbfs   BETWEEN -160 AND 0),
    ADD COLUMN cell_rat         text             CHECK (cell_rat IN ('lte', 'nr')),
    ADD COLUMN cell_rsrp_dbm    double precision CHECK (cell_rsrp_dbm BETWEEN -156 AND -31),
    ADD COLUMN cell_rsrq_db     double precision CHECK (cell_rsrq_db  BETWEEN -43 AND 20),
    ADD COLUMN cell_sinr_db     double precision CHECK (cell_sinr_db  BETWEEN -23 AND 40),
    ADD COLUMN cell_band        integer          CHECK (cell_band     BETWEEN 1 AND 1024),
    ADD COLUMN net_via          text             CHECK (net_via IN ('wifi', 'cell', 'ethernet', 'other')),
    ADD COLUMN net_vpn          boolean,
    ADD COLUMN net_rtt_ms       double precision CHECK (net_rtt_ms BETWEEN 0 AND 60000),
    -- A coordinate is a pair, and an altitude accuracy belongs to an altitude.
    ADD CONSTRAINT sample_location_whole CHECK ((lat IS NULL) = (lon IS NULL) AND (lat IS NULL) = (loc_acc_m IS NULL)),
    ADD CONSTRAINT sample_altitude_whole CHECK (alt_acc_m IS NULL OR alt_msl_m IS NOT NULL);

-- Something that happened once, at a moment. The envelope matches `sample`'s so
-- the same identity rule and the same boot anchors apply; `time` is the start.
CREATE TABLE event (
    time         timestamptz      NOT NULL,
    device_id    text             NOT NULL REFERENCES device (device_id),
    boot_id      bigint           NOT NULL,
    seq          bigint           NOT NULL,
    device_time  timestamptz,
    received_at  timestamptz      NOT NULL,
    uptime_ms    bigint           NOT NULL,
    quality      integer          NOT NULL DEFAULT 0,
    firmware     text             NOT NULL,

    kind         text             NOT NULL CHECK (kind IN ('vibration')),
    duration_ms  integer          NOT NULL CHECK (duration_ms BETWEEN 1 AND 600000),
    pga_ms2      double precision NOT NULL CHECK (pga_ms2 BETWEEN 0 AND 160),
    sta_lta      double precision NOT NULL CHECK (sta_lta BETWEEN 1 AND 1000),
    freq_hz      double precision CHECK (freq_hz BETWEEN 0 AND 100)
);

SELECT create_hypertable('event', 'time');

CREATE INDEX event_identity_lookup ON event (device_id, boot_id, seq);
CREATE UNIQUE INDEX event_identity ON event (device_id, boot_id, seq, time);
CREATE INDEX event_device_time ON event (device_id, time DESC);

-- Alerts are a log, not a flag: each row is one episode, from raised to cleared.
-- A plain table rather than a hypertable, because there are a handful a week.
CREATE TABLE alert (
    id          bigserial        PRIMARY KEY,
    kind        text             NOT NULL,
    device_id   text             NOT NULL REFERENCES device (device_id),
    raised_at   timestamptz      NOT NULL,
    cleared_at  timestamptz,
    value       double precision NOT NULL,
    threshold   double precision NOT NULL,
    message     text             NOT NULL,
    CONSTRAINT alert_clears_after_raising CHECK (cleared_at IS NULL OR cleared_at >= raised_at)
);

-- At most one open episode per kind and device. The evaluator relies on it, and
-- two ingestion processes racing each other cannot leave duplicate open alerts.
CREATE UNIQUE INDEX alert_one_open ON alert (kind, device_id) WHERE cleared_at IS NULL;
CREATE INDEX alert_recent ON alert (raised_at DESC);
