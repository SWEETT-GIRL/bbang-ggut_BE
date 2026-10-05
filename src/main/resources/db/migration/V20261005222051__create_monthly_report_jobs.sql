-- 미확정 정책을 자동으로 채우지 않는다. 검증된 초안만 작업으로 등록한다.
CREATE TABLE monthly_report_job (
    member_id bigint NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    year_month varchar(7) NOT NULL CHECK (year_month ~ '^20[0-9]{2}-(0[1-9]|1[0-2])$'),
    status varchar(16) NOT NULL CHECK (status IN ('READY', 'FAILED', 'COMPLETED', 'CANCELLED')),
    draft jsonb NOT NULL CHECK (jsonb_typeof(draft) = 'object'),
    raw_metrics jsonb NOT NULL CHECK (jsonb_typeof(raw_metrics) = 'object'),
    calculation_version varchar(100) NOT NULL CHECK (length(trim(calculation_version)) > 0),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    last_attempt_at timestamptz,
    last_error_code varchar(100),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (member_id, year_month)
);
CREATE INDEX ix_monthly_report_job_pending ON monthly_report_job(year_month, next_attempt_at, member_id)
    WHERE status IN ('READY', 'FAILED');
