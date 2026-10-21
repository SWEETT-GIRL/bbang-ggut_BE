-- Controller-owned runtime observation. It does not authorize deployment or serving writes.
CREATE TABLE response_agent_active_runtime (
    service_id VARCHAR(96) PRIMARY KEY REFERENCES response_agent_release_service(service_id),
    generation VARCHAR(96) NOT NULL,
    release_id VARCHAR(96) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    parser_digest CHAR(64) NOT NULL,
    profile_digest CHAR(64) NOT NULL,
    contract_digest CHAR(64) NOT NULL,
    database_identity CHAR(64) NOT NULL,
    dataops_database_identity CHAR(64) NOT NULL,
    serving_database_identity CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','DRAINING','STOPPED')),
    fence_epoch BIGINT NOT NULL CHECK (fence_epoch > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
REVOKE ALL ON response_agent_active_runtime FROM PUBLIC;
COMMENT ON TABLE response_agent_active_runtime IS
    'Trusted controller records an authenticated runtime probe under its service advisory lock. Execution can only use the current ACTIVE generation. No model/worker write grants.';
