-- Local, append-per-attempt journal for the scoped profile/feature publisher.
-- No serving/proposal FK: intents must outlive target and proposal changes.
CREATE TABLE kind_evidence_publish_attempt (
    id UUID PRIMARY KEY,
    target TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    manifest JSONB NOT NULL,
    before_image JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    error TEXT,
    CONSTRAINT kind_evidence_publish_status CHECK
        (status IN ('PREPARED', 'APPLYING', 'APPLIED', 'ROLLED_BACK', 'FAILED', 'UNKNOWN'))
);

CREATE UNIQUE INDEX idx_kind_evidence_publish_unresolved
    ON kind_evidence_publish_attempt (target)
    WHERE status IN ('PREPARED', 'APPLYING', 'UNKNOWN');

CREATE INDEX idx_kind_evidence_publish_target_created
    ON kind_evidence_publish_attempt (target, created_at DESC);

COMMENT ON TABLE kind_evidence_publish_attempt IS
    'Local intent before scoped serving DML; unresolved outcomes require reconciliation before another publish.';
