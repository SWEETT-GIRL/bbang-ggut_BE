-- Do not edit the original ledger migration: it may already be in Flyway history.
-- Its evidence_at was proposal.created_at, a processing time that can be much
-- later than the snapshot being re-evaluated. Preserve that value as provenance,
-- but never use it as a fallback observation boundary.
ALTER TABLE menu_evidence_legacy_removal
    ALTER COLUMN evidence_at DROP NOT NULL,
    ADD COLUMN snapshot_id BIGINT,
    ADD COLUMN proposal_created_at TIMESTAMP,
    ADD COLUMN time_basis VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

WITH resolved AS (
    SELECT legacy.proposal_id,
           proposal.snapshot_id,
           CASE
               WHEN proposal.store_id = legacy.store_id
                AND proposal.source = legacy.source
                AND proposal.target = 'menu.' || legacy.menu_id || '.removed'
                AND snapshot.store_id = legacy.store_id
                AND snapshot.source = legacy.source
               THEN snapshot.fetched_at
               ELSE NULL
           END AS observed_at
    FROM menu_evidence_legacy_removal legacy
    LEFT JOIN change_proposal proposal ON proposal.id = legacy.proposal_id
    LEFT JOIN place_snapshot snapshot ON snapshot.id = proposal.snapshot_id
)
UPDATE menu_evidence_legacy_removal legacy
SET proposal_created_at = legacy.evidence_at,
    snapshot_id = resolved.snapshot_id,
    evidence_at = resolved.observed_at,
    time_basis = CASE WHEN resolved.observed_at IS NOT NULL
        THEN 'SNAPSHOT_FETCHED_AT' ELSE 'UNKNOWN' END
FROM resolved
WHERE legacy.proposal_id = resolved.proposal_id;

ALTER TABLE menu_evidence_legacy_removal
    ADD CONSTRAINT menu_evidence_legacy_time_basis CHECK (
        (time_basis = 'SNAPSHOT_FETCHED_AT' AND evidence_at IS NOT NULL AND snapshot_id IS NOT NULL)
        OR (time_basis = 'UNKNOWN' AND evidence_at IS NULL)
    );

COMMENT ON COLUMN menu_evidence_legacy_removal.evidence_at IS
    'Referenced snapshot fetched_at only after proposal target/store/source and snapshot store/source match; NULL remains UNKNOWN hold.';
COMMENT ON COLUMN menu_evidence_legacy_removal.snapshot_id IS
    'Preserved candidate proposal snapshot reference, intentionally no FK; time_basis indicates whether it was verified.';
COMMENT ON COLUMN menu_evidence_legacy_removal.proposal_created_at IS
    'Original proposal creation time captured by the first migration; audit provenance only, never observation time.';
COMMENT ON COLUMN menu_evidence_legacy_removal.time_basis IS
    'SNAPSHOT_FETCHED_AT or UNKNOWN; missing/mismatched references retain the tombstone without inventing an observation timestamp.';
