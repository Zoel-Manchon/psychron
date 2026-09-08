-- Continuous aggregates. The dashboard never scans raw rows for a 30-day range:
-- it reads the bucket size that matches the window it is drawing.

CREATE MATERIALIZED VIEW reading_1m
    WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 minute', time)          AS bucket,
    device_id,
    avg(temperature_c)                     AS temp_avg,
    min(temperature_c)                     AS temp_min,
    max(temperature_c)                     AS temp_max,
    avg(humidity_pct)                      AS hum_avg,
    min(humidity_pct)                      AS hum_min,
    max(humidity_pct)                      AS hum_max,
    count(*)                               AS samples,
    count(*) FILTER (WHERE temperature_c IS NULL) AS null_samples
FROM reading
GROUP BY bucket, device_id
WITH NO DATA;

CREATE MATERIALIZED VIEW reading_1h
    WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time)            AS bucket,
    device_id,
    avg(temperature_c)                     AS temp_avg,
    min(temperature_c)                     AS temp_min,
    max(temperature_c)                     AS temp_max,
    stddev_samp(temperature_c)             AS temp_sd,
    avg(humidity_pct)                      AS hum_avg,
    min(humidity_pct)                      AS hum_min,
    max(humidity_pct)                      AS hum_max,
    stddev_samp(humidity_pct)              AS hum_sd,
    count(*)                               AS samples
FROM reading
GROUP BY bucket, device_id
WITH NO DATA;

CREATE MATERIALIZED VIEW reading_1d
    WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', time)             AS bucket,
    device_id,
    avg(temperature_c)                     AS temp_avg,
    min(temperature_c)                     AS temp_min,
    max(temperature_c)                     AS temp_max,
    avg(humidity_pct)                      AS hum_avg,
    min(humidity_pct)                      AS hum_min,
    max(humidity_pct)                      AS hum_max,
    count(*)                               AS samples
FROM reading
GROUP BY bucket, device_id
WITH NO DATA;

-- Refresh windows lag slightly behind now() so a bucket is only recomputed once the
-- readings that belong in it have arrived, including replays from the offline buffer.
SELECT add_continuous_aggregate_policy('reading_1m',
    start_offset => INTERVAL '3 hours',
    end_offset   => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');

SELECT add_continuous_aggregate_policy('reading_1h',
    start_offset => INTERVAL '3 days',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '10 minutes');

SELECT add_continuous_aggregate_policy('reading_1d',
    start_offset => INTERVAL '30 days',
    end_offset   => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 hour');

-- Raw readings compress after a week; the aggregates carry the long view. Nothing is
-- dropped — there is no retention policy on purpose, since the whole point of the
-- project is the long record.
ALTER TABLE reading SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id'
);

SELECT add_compression_policy('reading', INTERVAL '7 days');
