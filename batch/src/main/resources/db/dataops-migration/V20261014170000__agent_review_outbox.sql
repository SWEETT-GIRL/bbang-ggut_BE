-- Trusted review authority is separate from model output and from serving write permission.
CREATE TABLE response_agent_review_manifest (
    manifest_digest CHAR(64) PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES response_agent_run(run_id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    kind TEXT NOT NULL CHECK (kind IN ('PARSER_REPAIR', 'DATA_APPLY', 'REPORT_ONLY')),
    scope TEXT NOT NULL,
    document JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    review_state TEXT NOT NULL CHECK(review_state IN ('VALIDATED','HELD','SUPERSEDED','REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((kind='PARSER_REPAIR' AND scope='DEPLOY_REPLAY_PROPOSE') OR
           (kind='DATA_APPLY' AND scope='APPLY_EXACT_MANIFEST') OR (kind='REPORT_ONLY' AND scope='NONE')),
    UNIQUE(run_id,revision,kind)
);
CREATE TABLE response_agent_review_publication (
    manifest_digest CHAR(64) PRIMARY KEY REFERENCES response_agent_review_manifest(manifest_digest),
    repository TEXT NOT NULL,
    pr_number BIGINT NOT NULL CHECK (pr_number > 0),
    head_sha CHAR(40) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(repository,pr_number,head_sha)
);
CREATE TABLE response_agent_review_approval (
    approval_digest CHAR(64) PRIMARY KEY,
    manifest_digest CHAR(64) NOT NULL UNIQUE REFERENCES response_agent_review_publication(manifest_digest),
    event_key TEXT NOT NULL UNIQUE,
    actor_id BIGINT NOT NULL CHECK(actor_id > 0),
    scope TEXT NOT NULL CHECK(scope IN ('DEPLOY_REPLAY_PROPOSE','APPLY_EXACT_MANIFEST')),
    evidence JSONB NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('APPROVED','CONSUMED','REVOKED')),
    operation_id TEXT UNIQUE,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at TIMESTAMPTZ,
    CHECK ((state='CONSUMED' AND operation_id IS NOT NULL AND consumed_at IS NOT NULL)
        OR (state<>'CONSUMED' AND operation_id IS NULL AND consumed_at IS NULL))
);
CREATE TABLE response_agent_notification_outbox (
    event_id UUID PRIMARY KEY,
    event_key CHAR(64) NOT NULL UNIQUE,
    manifest_digest CHAR(64) NOT NULL REFERENCES response_agent_review_manifest(manifest_digest),
    event_kind TEXT NOT NULL,
    destination TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK(revision > 0),
    payload_digest CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    marker TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','SENDING','DELIVERED','DELIVERY_UNKNOWN')),
    owner TEXT,
    fencing_epoch BIGINT NOT NULL DEFAULT 0 CHECK(fencing_epoch >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provider_reference JSONB,
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX response_agent_notification_due ON response_agent_notification_outbox(next_attempt_at,lease_until)
    WHERE state IN ('PENDING','SENDING','DELIVERY_UNKNOWN');
COMMENT ON TABLE response_agent_notification_outbox IS 'Enqueue in the same transaction as the host state transition. Delivery recovery must not invoke the model.';
