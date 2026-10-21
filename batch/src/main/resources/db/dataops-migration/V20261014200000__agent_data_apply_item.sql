-- Exact approval-to-proposal operation bindings. Existing proposal/apply_log keep write outcomes.
CREATE TABLE response_agent_data_apply_item (
    approval_digest CHAR(64) NOT NULL REFERENCES response_agent_review_approval(approval_digest),
    operation_id UUID NOT NULL,
    proposal_id BIGINT NOT NULL REFERENCES change_proposal(id),
    proposal_operation_id UUID NOT NULL UNIQUE,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(approval_digest,proposal_id),
    UNIQUE(operation_id,proposal_id)
);
CREATE FUNCTION response_agent_data_apply_item_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'data approval item history is immutable';
END;
$$;
CREATE TRIGGER response_agent_data_apply_item_immutable BEFORE UPDATE OR DELETE ON response_agent_data_apply_item
    FOR EACH ROW EXECUTE FUNCTION response_agent_data_apply_item_immutable();
