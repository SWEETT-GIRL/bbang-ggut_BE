-- Only the trusted release controller may issue execution authority. No model/worker grants are made.
CREATE TABLE response_agent_execution_binding (
    release_operation_id VARCHAR(96) PRIMARY KEY REFERENCES response_agent_release_operation(operation_id),
    replay_operation_id UUID NOT NULL UNIQUE,
    manifest_digest CHAR(64) NOT NULL REFERENCES response_agent_review_manifest(manifest_digest),
    approval_digest CHAR(64) NOT NULL REFERENCES response_agent_review_approval(approval_digest),
    service_id VARCHAR(96) NOT NULL REFERENCES response_agent_release_service(service_id),
    release_id VARCHAR(96) NOT NULL,
    generation VARCHAR(96) NOT NULL,
    artifact_digest CHAR(64) NOT NULL,
    parser_digest CHAR(64) NOT NULL,
    profile_digest CHAR(64) NOT NULL,
    contract_digest CHAR(64) NOT NULL,
    contract_version TEXT NOT NULL,
    policy_digest CHAR(64) NOT NULL,
    dataops_database_identity CHAR(64) NOT NULL,
    serving_database_identity CHAR(64) NOT NULL,
    database_identity CHAR(64) NOT NULL,
    validation_json TEXT NOT NULL CHECK (octet_length(validation_json) <= 2097152),
    validation_digest CHAR(64) NOT NULL,
    plan_json TEXT NOT NULL CHECK (octet_length(plan_json) <= 2097152),
    plan_digest CHAR(64) NOT NULL,
    canary_policy_json TEXT NOT NULL CHECK (octet_length(canary_policy_json) <= 262144),
    canary_policy_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (release_operation_id ~ '^release-[0-9a-f]{32}$')
);
REVOKE ALL ON response_agent_execution_binding FROM PUBLIC;
CREATE FUNCTION response_agent_execution_binding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'execution authority binding is immutable';
END;
$$;
CREATE TRIGGER response_agent_execution_binding_immutable BEFORE UPDATE OR DELETE ON response_agent_execution_binding
    FOR EACH ROW EXECUTE FUNCTION response_agent_execution_binding_immutable();
COMMENT ON TABLE response_agent_execution_binding IS
    'Controller-only INSERT; execution role SELECT only. Immutable parser approval to full-capture replay UUID/oracle binding. No worker authority grants.';
