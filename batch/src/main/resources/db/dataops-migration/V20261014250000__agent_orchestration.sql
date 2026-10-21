-- Scheduling is not approval authority. These rows only reference trusted inputs.
CREATE TABLE response_agent_orchestration_job (
    job_id char(64) PRIMARY KEY,
    service_id varchar(96) NOT NULL,
    kind text NOT NULL CHECK (kind IN ('PREPARE_PARSER','ADVANCE_REVIEW','PREPARE_DATA','PROMOTE_BASELINE')),
    subject text NOT NULL,
    run_id uuid NOT NULL REFERENCES response_agent_run(run_id),
    request jsonb NOT NULL CHECK (jsonb_typeof(request)='object'),
    state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','WAITING','RUNNING','DONE','HELD','UNKNOWN')),
    attempt bigint NOT NULL DEFAULT 0 CHECK (attempt>=0),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(result)='object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(kind,subject)
);
CREATE INDEX response_agent_orchestration_due ON response_agent_orchestration_job(service_id,next_attempt_at)
    WHERE state IN ('READY','WAITING');
CREATE TABLE response_agent_orchestration_event (
    job_id char(64) NOT NULL REFERENCES response_agent_orchestration_job(job_id),
    sequence bigint NOT NULL CHECK (sequence>0),
    event text NOT NULL CHECK (event IN ('START','FINISH','INTERRUPTED','RECONCILED')),
    detail jsonb NOT NULL CHECK (jsonb_typeof(detail)='object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(job_id,sequence)
);
CREATE FUNCTION protect_agent_orchestration_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (NEW.job_id,NEW.service_id,NEW.kind,NEW.subject,NEW.run_id,NEW.request,NEW.created_at)
        IS DISTINCT FROM (OLD.job_id,OLD.service_id,OLD.kind,OLD.subject,OLD.run_id,OLD.request,OLD.created_at) THEN
        RAISE EXCEPTION 'orchestration identity is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER response_agent_orchestration_identity BEFORE UPDATE OR DELETE ON response_agent_orchestration_job
    FOR EACH ROW EXECUTE FUNCTION protect_agent_orchestration_identity();
CREATE FUNCTION protect_agent_orchestration_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'orchestration events are append only'; END $$;
CREATE TRIGGER response_agent_orchestration_event_immutable BEFORE UPDATE OR DELETE ON response_agent_orchestration_event
    FOR EACH ROW EXECUTE FUNCTION protect_agent_orchestration_event();
COMMENT ON TABLE response_agent_orchestration_job IS 'A lost RUNNING attempt becomes UNKNOWN. Scheduling never creates approval authority or automatically retries unknown effects.';
