-- Informational PR comments cannot change manifest bytes or approval authority.
CREATE TABLE response_agent_status_notification (
    event_key char(64) PRIMARY KEY,
    job_id char(64) NOT NULL,
    event_sequence bigint NOT NULL,
    run_id uuid NOT NULL REFERENCES response_agent_run(run_id),
    manifest_digest char(64) REFERENCES response_agent_review_manifest(manifest_digest),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    payload_digest char(64) NOT NULL,
    marker text NOT NULL UNIQUE,
    state text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENDING','DELIVERED','DELIVERY_UNKNOWN')),
    owner text,
    fencing_epoch bigint NOT NULL DEFAULT 0 CHECK (fencing_epoch>=0),
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provider_reference jsonb,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(job_id,event_sequence) REFERENCES response_agent_orchestration_event(job_id,sequence),
    UNIQUE(job_id,payload_digest)
);
CREATE INDEX response_agent_status_notification_due ON response_agent_status_notification(next_attempt_at,lease_until)
    WHERE state IN ('PENDING','SENDING','DELIVERY_UNKNOWN');
CREATE FUNCTION protect_agent_status_notification_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (NEW.event_key,NEW.job_id,NEW.event_sequence,NEW.run_id,NEW.manifest_digest,
        NEW.payload,NEW.payload_digest,NEW.marker,NEW.created_at) IS DISTINCT FROM
        (OLD.event_key,OLD.job_id,OLD.event_sequence,OLD.run_id,OLD.manifest_digest,
        OLD.payload,OLD.payload_digest,OLD.marker,OLD.created_at) THEN
        RAISE EXCEPTION 'status notification identity is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER response_agent_status_notification_identity BEFORE UPDATE OR DELETE ON response_agent_status_notification
    FOR EACH ROW EXECUTE FUNCTION protect_agent_status_notification_identity();
