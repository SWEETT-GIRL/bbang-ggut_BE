-- Durable process-launch boundary. A duplicate merge cannot launch a second apply JVM.
CREATE TABLE response_agent_data_execution (
    approval_digest CHAR(64) PRIMARY KEY REFERENCES response_agent_review_approval(approval_digest),
    operation_id UUID NOT NULL UNIQUE,
    manifest_digest CHAR(64) NOT NULL REFERENCES response_agent_review_manifest(manifest_digest),
    release_operation_id VARCHAR(96) NOT NULL REFERENCES response_agent_execution_binding(release_operation_id),
    request_digest CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('INTENT','FINISHED','UNKNOWN')),
    exit_code INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
REVOKE ALL ON response_agent_data_execution FROM PUBLIC;
CREATE FUNCTION response_agent_data_execution_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR ROW(NEW.approval_digest,NEW.operation_id,NEW.manifest_digest,NEW.release_operation_id,NEW.request_digest)
        IS DISTINCT FROM ROW(OLD.approval_digest,OLD.operation_id,OLD.manifest_digest,OLD.release_operation_id,OLD.request_digest) THEN
        RAISE EXCEPTION 'data execution identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER response_agent_data_execution_identity_immutable BEFORE UPDATE OR DELETE ON response_agent_data_execution
    FOR EACH ROW EXECUTE FUNCTION response_agent_data_execution_identity_immutable();
