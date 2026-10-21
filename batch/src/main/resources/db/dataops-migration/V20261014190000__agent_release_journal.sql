-- Controller journal only. Does not grant operating writes or start any service.
CREATE TABLE response_agent_release_service (
    service_id varchar(96) PRIMARY KEY,
    fencing_epoch bigint NOT NULL DEFAULT 0 CHECK (fencing_epoch >= 0),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE response_agent_release_operation (
    operation_id varchar(96) PRIMARY KEY,
    service_id varchar(96) NOT NULL REFERENCES response_agent_release_service(service_id),
    approval_digest char(64) NOT NULL UNIQUE REFERENCES response_agent_review_approval(approval_digest),
    manifest_digest char(64) NOT NULL REFERENCES response_agent_review_manifest(manifest_digest),
    request_digest char(64) NOT NULL,
    approval_scope varchar(40) NOT NULL CHECK (approval_scope = 'DEPLOY_REPLAY_PROPOSE'),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    state varchar(40) NOT NULL CHECK (state IN (
        'QUEUED', 'PREPARE_IN_FLIGHT', 'PREPARED', 'DRAIN_IN_FLIGHT', 'DRAINED',
        'START_IN_FLIGHT', 'VERIFYING', 'CANARY', 'CANARY_IN_FLIGHT',
        'REPLAY', 'REPLAY_IN_FLIGHT', 'ROLLBACK_NEEDED', 'ROLLBACK_IN_FLIGHT',
        'ACTIVE', 'STALE', 'HELD', 'ROLLED_BACK', 'REPLAY_HELD', 'RECOVERY_REQUIRED')),
    pending_action varchar(16) CHECK (pending_action IN ('prepare', 'drain', 'start', 'canary', 'replay', 'rollback')),
    receipt jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(receipt) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX response_agent_release_operation_service_idx
    ON response_agent_release_operation(service_id, created_at);
