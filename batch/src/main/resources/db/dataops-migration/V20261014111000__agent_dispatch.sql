-- Agent scheduling state is independent of observations and serving writes.
-- One run per captured structural incident + exact deployed parser/field contract.
CREATE TABLE response_agent_run (
    run_id UUID PRIMARY KEY,
    fingerprint CHAR(64) NOT NULL REFERENCES response_incident(fingerprint),
    parser_digest TEXT NOT NULL,
    contract_version TEXT NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'AWAITING_REVIEW', 'HELD', 'ERROR')),
    owner TEXT NOT NULL,
    fencing_epoch BIGINT NOT NULL DEFAULT 0 CHECK (fencing_epoch >= 0),
    lease_until TIMESTAMPTZ NOT NULL,
    budget_usd NUMERIC(12, 8) NOT NULL CHECK (budget_usd > 0),
    bundle_digest CHAR(64),
    run_directory TEXT NOT NULL,
    checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    stop_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (fingerprint, parser_digest, contract_version)
);
CREATE INDEX response_agent_run_lease ON response_agent_run (lease_until) WHERE state IN ('QUEUED', 'RUNNING');
COMMENT ON TABLE response_agent_run IS 'Fenced dispatcher journal only; no approval, parser deployment, or serving mutation authority.';
