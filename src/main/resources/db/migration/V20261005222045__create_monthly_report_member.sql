CREATE TABLE monthly_report_member (
    member_id BIGINT NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    year_month VARCHAR(7) NOT NULL,
    status VARCHAR(16) NOT NULL,
    payload JSONB,
    raw_metrics JSONB,
    calculation_version VARCHAR(100),
    generated_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    PRIMARY KEY (member_id, year_month),
    CONSTRAINT ck_monthly_report_month CHECK (year_month ~ '^20[0-9]{2}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_monthly_report_status CHECK (status IN ('WITHHELD', 'FAILED', 'PUBLISHED')),
    CONSTRAINT ck_monthly_report_publication CHECK (
        (status = 'PUBLISHED' AND payload IS NOT NULL AND jsonb_typeof(payload) = 'object'
            AND raw_metrics IS NOT NULL AND jsonb_typeof(raw_metrics) = 'object'
            AND calculation_version IS NOT NULL AND length(trim(calculation_version)) > 0
            AND generated_at IS NOT NULL AND published_at IS NOT NULL AND generated_at <= published_at)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

COMMENT ON TABLE monthly_report_member IS '개인 월간 빵일지 스냅샷. 정책과 원시 집계가 검증된 결과만 발행한다';
COMMENT ON COLUMN monthly_report_member.year_month IS 'Asia/Seoul 기준 보고서 월 (YYYY-MM)';
COMMENT ON COLUMN monthly_report_member.raw_metrics IS '산식 적용 전 집계 및 완전성 검증 메타데이터';
COMMENT ON COLUMN monthly_report_member.generated_at IS '원시 데이터 집계 완료 시각';
