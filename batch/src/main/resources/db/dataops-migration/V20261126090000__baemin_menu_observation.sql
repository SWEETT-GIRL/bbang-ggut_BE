-- Separate comparison baseline; unknown historical menu provenance is not backfilled.
ALTER TABLE store_sync_state ADD COLUMN baemin_menu_hash VARCHAR(64);
ALTER TABLE store_sync_state ADD COLUMN baemin_menu_observed_at TIMESTAMP;
