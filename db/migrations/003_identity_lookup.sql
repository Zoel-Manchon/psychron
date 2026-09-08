-- Ingestion checks whether a reading is already stored by (device, boot, seq)
-- alone, without a time bound: the unique index has to include time because the
-- table is partitioned on it, so a redelivery whose instant resolved differently
-- the second time would slip past it and be stored twice.
--
-- This index is what makes that check a lookup rather than a scan of the whole
-- hypertable on every single message.

CREATE INDEX IF NOT EXISTS reading_identity_lookup
    ON reading (device_id, boot_id, seq);
