-- Continuous aggregates default to materialized_only = true from TimescaleDB
-- 2.13 onwards, which means a query against one sees only the buckets the
-- refresh policy has already written. The policies deliberately lag behind now()
-- so a bucket is not computed before the readings that belong in it have
-- arrived — including replays drained from a node's flash buffer hours late.
--
-- The two together are a trap: any dashboard range long enough to use an
-- aggregate silently stops an hour or a day short of the present, and a chart
-- that ends in the past is indistinguishable from a sensor that died.
--
-- Real-time aggregation unions the materialized buckets with a live aggregate
-- over the raw rows that are still too recent to have been rolled up. It costs
-- a little query time at the tail and removes the blind spot entirely.

ALTER MATERIALIZED VIEW reading_1m SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW reading_1h SET (timescaledb.materialized_only = false);
ALTER MATERIALIZED VIEW reading_1d SET (timescaledb.materialized_only = false);
