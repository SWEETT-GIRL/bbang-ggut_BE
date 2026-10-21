-- Additive migration: do not change the previously applied raw-capture migration.
ALTER TABLE response_capture DROP CONSTRAINT response_capture_status;
ALTER TABLE response_capture ADD CONSTRAINT response_capture_status
    CHECK (status IN ('RECEIVED', 'READY', 'PARTIAL', 'HELD'));
ALTER TABLE response_capture ADD COLUMN target_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE response_capture ADD COLUMN processed_response TEXT;
ALTER TABLE response_capture ADD COLUMN processed_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX response_capture_observed ON response_capture
    (store_id, source, external_id, observed_at DESC, received_at DESC);
ALTER TABLE store_sync_state ADD COLUMN non_menu_hash VARCHAR(64);
ALTER TABLE store_sync_state ADD COLUMN menu_hash VARCHAR(64);
ALTER TABLE store_sync_state ADD COLUMN scope_hash_version SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE store_sync_state ADD COLUMN store_fields_observed_at TIMESTAMP;
ALTER TABLE store_sync_state ADD COLUMN menu_observed_at TIMESTAMP;
COMMENT ON COLUMN response_capture.processed_response IS 'Completed receipt committed with downstream state, not with independent raw assessment.';
COMMENT ON COLUMN store_sync_state.content_hash IS 'Last complete content baseline; PARTIAL observations do not overwrite it.';

-- Prior captures already have server-generated diagnostics; preserve their latest-observation barrier.
UPDATE response_capture SET target_verified = TRUE
WHERE status IN ('READY', 'HELD') AND contract_version = 'naver-response-v1'
  AND diagnostics IS NOT NULL AND parser_digest IS NOT NULL
  AND diagnostics NOT LIKE '%UNVERIFIED_INPUT%' AND diagnostics NOT LIKE '%ROOT_UNKNOWN%'
  AND diagnostics NOT LIKE '%ARGUMENTS_UNSUPPORTED%' AND diagnostics NOT LIKE '%ROOT_TYPE_CHANGED%'
  AND diagnostics NOT LIKE '%AMBIGUOUS_BASE%' AND diagnostics NOT LIKE '%IDENTITY_UNKNOWN%'
  AND diagnostics NOT LIKE '%PARSER_EXCEPTION%';
