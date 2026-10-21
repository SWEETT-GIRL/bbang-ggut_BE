-- Reservations belong to incident lineage and the Asia/Seoul day at reservation,
-- not to a worker, run revision, response time or current parser version.
CREATE TABLE response_agent_budget_policy (
    policy_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (policy_id),
    legacy_reconciliation_required BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO response_agent_budget_policy(policy_id, legacy_reconciliation_required)
SELECT TRUE, EXISTS (SELECT 1 FROM response_agent_run
                    WHERE jsonb_array_length(COALESCE(checkpoint->'requests','[]'::jsonb)) > 0);

CREATE TABLE response_agent_budget_incident (
    budget_incident_id CHAR(64) PRIMARY KEY,
    source VARCHAR(20) NOT NULL,
    identity_version TEXT NOT NULL,
    budget_usd NUMERIC(12,8) NOT NULL CHECK (budget_usd > 0 AND budget_usd <= 0.50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE response_agent_budget_alias (
    fingerprint CHAR(64) PRIMARY KEY REFERENCES response_incident(fingerprint),
    budget_incident_id CHAR(64) NOT NULL REFERENCES response_agent_budget_incident(budget_incident_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (fingerprint,budget_incident_id)
);
ALTER TABLE response_agent_run ADD COLUMN budget_incident_id CHAR(64);
ALTER TABLE response_agent_run ADD CONSTRAINT response_agent_run_budget_binding
    FOREIGN KEY (fingerprint,budget_incident_id)
    REFERENCES response_agent_budget_alias(fingerprint,budget_incident_id);
ALTER TABLE response_agent_run ADD CONSTRAINT response_agent_run_budget_identity UNIQUE (run_id,budget_incident_id);
CREATE TABLE response_agent_budget_day (
    budget_day_kst DATE PRIMARY KEY,
    budget_usd NUMERIC(12,8) NOT NULL CHECK (budget_usd > 0 AND budget_usd <= 2.00),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE response_agent_budget_request (
    run_id UUID NOT NULL REFERENCES response_agent_run(run_id),
    request_id TEXT NOT NULL,
    budget_incident_id CHAR(64) NOT NULL REFERENCES response_agent_budget_incident(budget_incident_id),
    budget_day_kst DATE NOT NULL REFERENCES response_agent_budget_day(budget_day_kst),
    reserved_usd NUMERIC(12,8) NOT NULL CHECK (reserved_usd >= 0),
    reservation JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id,request_id),
    FOREIGN KEY (run_id,budget_incident_id) REFERENCES response_agent_run(run_id,budget_incident_id)
);
CREATE INDEX response_agent_budget_request_incident ON response_agent_budget_request(budget_incident_id);
CREATE INDEX response_agent_budget_request_day ON response_agent_budget_request(budget_day_kst);
CREATE TABLE response_agent_budget_settlement (
    run_id UUID NOT NULL,
    request_id TEXT NOT NULL,
    cost_usd NUMERIC(12,8) NOT NULL CHECK (cost_usd >= 0),
    receipt JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id,request_id),
    FOREIGN KEY (run_id,request_id) REFERENCES response_agent_budget_request(run_id,request_id)
);
-- Worker credentials receive SELECT/INSERT on append-only journals and aliases.
-- Database enforcement also prevents a future broad UPDATE grant erasing history.
CREATE FUNCTION response_agent_budget_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent budget history is immutable';
END;
$$;
CREATE TRIGGER response_agent_budget_request_immutable BEFORE UPDATE OR DELETE
    ON response_agent_budget_request FOR EACH ROW EXECUTE FUNCTION response_agent_budget_immutable();
CREATE TRIGGER response_agent_budget_settlement_immutable BEFORE UPDATE OR DELETE
    ON response_agent_budget_settlement FOR EACH ROW EXECUTE FUNCTION response_agent_budget_immutable();
CREATE TRIGGER response_agent_budget_alias_immutable BEFORE UPDATE OR DELETE
    ON response_agent_budget_alias FOR EACH ROW EXECUTE FUNCTION response_agent_budget_immutable();
COMMENT ON TABLE response_agent_budget_policy IS
    'A migration finding legacy paid checkpoints fails closed globally until explicit cost/day reconciliation; do not clear merely to resume.';
COMMENT ON TABLE response_agent_budget_incident IS
    'Source/canonical structural diagnostic lineage, independent of run/parser/fingerprint. Lower limits persist; no automatic split or reset.';

CREATE FUNCTION response_agent_budget_no_increase() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.budget_usd > OLD.budget_usd THEN
        RAISE EXCEPTION 'agent budget increase requires separately approved policy';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER response_agent_budget_incident_no_increase BEFORE UPDATE
    ON response_agent_budget_incident FOR EACH ROW EXECUTE FUNCTION response_agent_budget_no_increase();
CREATE TRIGGER response_agent_budget_day_no_increase BEFORE UPDATE
    ON response_agent_budget_day FOR EACH ROW EXECUTE FUNCTION response_agent_budget_no_increase();
CREATE FUNCTION response_agent_run_budget_binding_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.budget_incident_id IS NOT NULL AND NEW.budget_incident_id IS DISTINCT FROM OLD.budget_incident_id THEN
        RAISE EXCEPTION 'agent run budget binding is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER response_agent_run_budget_binding_immutable BEFORE UPDATE
    ON response_agent_run FOR EACH ROW EXECUTE FUNCTION response_agent_run_budget_binding_immutable();
