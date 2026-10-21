-- Captured-raw replay is a separate append-only history, never a new observation.
CREATE TABLE response_replay_run (
    operation_id UUID PRIMARY KEY,
    manifest_digest CHAR(64) NOT NULL,
    release_id TEXT NOT NULL,
    artifact_digest CHAR(64) NOT NULL,
    parser_digest CHAR(64) NOT NULL,
    profile_digest CHAR(64) NOT NULL,
    contract_version TEXT NOT NULL,
    policy_digest CHAR(64) NOT NULL,
    approved_plan JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE response_replay_target (
    operation_id UUID NOT NULL REFERENCES response_replay_run(operation_id),
    capture_id VARCHAR(64) NOT NULL REFERENCES response_capture(capture_id),
    release_id TEXT NOT NULL,
    policy_digest CHAR(64) NOT NULL,
    target_json JSONB NOT NULL,
    is_canary BOOLEAN NOT NULL,
    PRIMARY KEY(operation_id,capture_id),
    UNIQUE(capture_id,release_id,policy_digest)
);
CREATE TABLE response_replay_receipt (
    operation_id UUID NOT NULL,
    capture_id VARCHAR(64) NOT NULL,
    phase VARCHAR(10) NOT NULL CHECK (phase IN ('CANARY','REPLAY')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PARSED','PROPOSED','NO_CHANGE','STALE','HELD')),
    receipt_json JSONB NOT NULL,
    original_capture_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(operation_id,capture_id,phase),
    FOREIGN KEY(operation_id,capture_id) REFERENCES response_replay_target(operation_id,capture_id)
);
CREATE TABLE response_replay_proposal (
    operation_id UUID NOT NULL,
    capture_id VARCHAR(64) NOT NULL,
    proposal_id BIGINT NOT NULL REFERENCES change_proposal(id),
    relation VARCHAR(10) NOT NULL CHECK (relation IN ('CREATED','REUSED')),
    original_proposal_json JSONB NOT NULL,
    phase VARCHAR(10) NOT NULL DEFAULT 'REPLAY' CHECK (phase='REPLAY'),
    PRIMARY KEY(operation_id,capture_id,proposal_id),
    FOREIGN KEY(operation_id,capture_id,phase) REFERENCES response_replay_receipt(operation_id,capture_id,phase)
);
CREATE TABLE response_replay_completion (
    operation_id UUID NOT NULL REFERENCES response_replay_run(operation_id),
    phase VARCHAR(10) NOT NULL CHECK (phase IN ('CANARY','REPLAY')),
    passed BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(operation_id,phase)
);
CREATE FUNCTION response_replay_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'capture replay history is immutable';
END;
$$;
CREATE TRIGGER response_replay_run_immutable BEFORE UPDATE OR DELETE ON response_replay_run
    FOR EACH ROW EXECUTE FUNCTION response_replay_immutable();
CREATE TRIGGER response_replay_target_immutable BEFORE UPDATE OR DELETE ON response_replay_target
    FOR EACH ROW EXECUTE FUNCTION response_replay_immutable();
CREATE TRIGGER response_replay_receipt_immutable BEFORE UPDATE OR DELETE ON response_replay_receipt
    FOR EACH ROW EXECUTE FUNCTION response_replay_immutable();
CREATE TRIGGER response_replay_proposal_immutable BEFORE UPDATE OR DELETE ON response_replay_proposal
    FOR EACH ROW EXECUTE FUNCTION response_replay_immutable();
CREATE TRIGGER response_replay_completion_immutable BEFORE UPDATE OR DELETE ON response_replay_completion
    FOR EACH ROW EXECUTE FUNCTION response_replay_immutable();
