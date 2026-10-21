CREATE TABLE IF NOT EXISTS contribution_report (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT REFERENCES member (id),
    target_type VARCHAR(20) NOT NULL,
    report_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_contribution_report_status_created_at
    ON contribution_report (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_contribution_report_member_id
    ON contribution_report (member_id)
    WHERE member_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_contribution_report_target_report_type
    ON contribution_report (target_type, report_type);
