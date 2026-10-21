-- Do not invent an outcome for historical unresolved writes or silently discard pending work.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM change_proposal WHERE status IN ('PENDING','APPLYING','UNKNOWN')
        GROUP BY store_id,target HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'UNRESOLVED_FENCE_PREFLIGHT: conflicting PENDING/APPLYING/UNKNOWN targets require explicit reconciliation';
    END IF;
    IF EXISTS (SELECT 1 FROM change_proposal WHERE status IN ('APPLYING','UNKNOWN') AND operation_id IS NULL) THEN
        RAISE EXCEPTION 'UNRESOLVED_FENCE_PREFLIGHT: unresolved proposal has no operation identity';
    END IF;
END $$;

CREATE TABLE proposal_apply_reconciliation (
    evidence_id UUID PRIMARY KEY,
    proposal_id BIGINT NOT NULL REFERENCES change_proposal(id),
    operation_id UUID NOT NULL,
    outcome TEXT NOT NULL CHECK(outcome IN ('APPLIED','NOT_APPLIED')),
    authority_event TEXT NOT NULL UNIQUE,
    serving_evidence_digest CHAR(64) NOT NULL CHECK(serving_evidence_digest ~ '^[0-9a-f]{64}$'),
    apply_log_evidence_digest CHAR(64) NOT NULL CHECK(apply_log_evidence_digest ~ '^[0-9a-f]{64}$'),
    writer_quiescence_digest CHAR(64) NOT NULL CHECK(writer_quiescence_digest ~ '^[0-9a-f]{64}$'),
    evidence JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(proposal_id,operation_id)
);
CREATE FUNCTION proposal_apply_reconciliation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'proposal apply reconciliation evidence is immutable';
END $$;
CREATE TRIGGER proposal_apply_reconciliation_immutable BEFORE UPDATE OR DELETE ON proposal_apply_reconciliation
    FOR EACH ROW EXECUTE FUNCTION proposal_apply_reconciliation_immutable();

DROP INDEX uq_proposal_in_flight;
CREATE UNIQUE INDEX uq_proposal_in_flight ON change_proposal(store_id,target)
    WHERE status IN ('PENDING','APPLYING','UNKNOWN');
ALTER TABLE change_proposal ADD CONSTRAINT change_proposal_unresolved_operation
    CHECK(status NOT IN ('APPLYING','UNKNOWN') OR operation_id IS NOT NULL);

CREATE FUNCTION change_proposal_preserve_unresolved_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('APPLYING','UNKNOWN') THEN
        IF TG_OP='DELETE' THEN
            RAISE EXCEPTION 'UNRESOLVED_TARGET_FENCE: unresolved proposal cannot be deleted';
        END IF;
        IF ROW(NEW.store_id,NEW.target,NEW.operation_id,NEW.old_value,NEW.new_value,NEW.producer)
            IS DISTINCT FROM ROW(OLD.store_id,OLD.target,OLD.operation_id,OLD.old_value,OLD.new_value,OLD.producer) THEN
            RAISE EXCEPTION 'UNRESOLVED_TARGET_FENCE: unresolved operation identity/content cannot change';
        END IF;
        IF NEW.status NOT IN ('APPLYING','UNKNOWN','APPROVED','FAILED') THEN
            RAISE EXCEPTION 'UNRESOLVED_TARGET_FENCE: unresolved proposal cannot be reset';
        END IF;
        IF OLD.status='UNKNOWN' AND NEW.status<>OLD.status AND NOT EXISTS (
            SELECT 1 FROM proposal_apply_reconciliation r WHERE r.proposal_id=OLD.id
            AND r.operation_id=OLD.operation_id
            AND ((r.outcome='APPLIED' AND NEW.status='APPROVED') OR (r.outcome='NOT_APPLIED' AND NEW.status='FAILED'))
        ) THEN
            RAISE EXCEPTION 'UNRESOLVED_TARGET_FENCE: authenticated reconciliation evidence required';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER change_proposal_preserve_unresolved_fence BEFORE UPDATE OR DELETE ON change_proposal
    FOR EACH ROW EXECUTE FUNCTION change_proposal_preserve_unresolved_fence();
