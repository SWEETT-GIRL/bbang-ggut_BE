-- A fetch is an observation even when the semantic snapshot hash is unchanged.
-- Application writes are INSERT-only; there is deliberately no proposal/snapshot FK.
CREATE TABLE menu_evidence_observation (
    id BIGSERIAL PRIMARY KEY,
    observation_id VARCHAR(64) NOT NULL UNIQUE,
    store_id BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL,
    external_id TEXT,
    observed_at TIMESTAMP NOT NULL,
    received_at TIMESTAMP NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    menu_names JSONB NOT NULL,
    representative_names JSONB NOT NULL,
    menu_complete BOOLEAN NOT NULL,
    identity_verified BOOLEAN NOT NULL,
    parser_version TEXT NOT NULL,
    CONSTRAINT menu_evidence_menu_array CHECK (jsonb_typeof(menu_names) = 'array'),
    CONSTRAINT menu_evidence_representative_array CHECK (jsonb_typeof(representative_names) = 'array'),
    CONSTRAINT menu_evidence_complete_identity CHECK (NOT menu_complete OR identity_verified)
);

CREATE INDEX idx_menu_evidence_replay
    ON menu_evidence_observation (store_id, source, observed_at, id);
CREATE INDEX idx_menu_evidence_latest_verified
    ON menu_evidence_observation (store_id, source, external_id, observed_at DESC, id DESC)
    WHERE identity_verified;

COMMENT ON TABLE menu_evidence_observation IS
    'Append-only fetch evidence, independent of snapshot deduplication and proposal lifecycle. All timestamps are Asia/Seoul local time.';

-- Preserve historical removal ambiguity before reconcile deletes/replaces proposals.
-- A copied removal is UNKNOWN, never proof of two independent missing observations.
CREATE TABLE menu_evidence_legacy_removal (
    proposal_id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    menu_id TEXT NOT NULL,
    menu_name TEXT,
    evidence_at TIMESTAMP NOT NULL,
    source VARCHAR(20) NOT NULL,
    status_at_capture VARCHAR(20) NOT NULL
);

INSERT INTO menu_evidence_legacy_removal
    (proposal_id, store_id, menu_id, menu_name, evidence_at, source, status_at_capture)
SELECT id, store_id, split_part(target, '.', 2), old_value ->> 'name', created_at, source, status
FROM change_proposal
WHERE source = 'NAVER'
  AND target ~ '^menu\.[0-9]+\.removed$'
  AND old_value ->> 'reason' IS NULL
  AND status IN ('PENDING', 'APPLYING', 'APPROVED');

CREATE INDEX idx_menu_evidence_legacy_store ON menu_evidence_legacy_removal (store_id);
COMMENT ON TABLE menu_evidence_legacy_removal IS
    'Immutable migration-time copy of ambiguous legacy removals; intentionally no proposal FK.';
