-- 회원 프로필 초기화 및 작성 제한 저장 구조 추가
-- 기존 DB에는 Flyway baseline 이후 자동 적용되고, 이미 수동 적용된 DB에서도 재실행 가능하도록 IF NOT EXISTS를 유지한다.

CREATE TABLE IF NOT EXISTS member_restriction (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES member (id),
    restriction_type VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_member_restriction_type CHECK (
        restriction_type IN ('WARNING', 'WRITE_RESTRICTED', 'PROFILE_RESTRICTED', 'SUSPENDED')
    )
);

CREATE INDEX IF NOT EXISTS idx_member_restriction_member_active
    ON member_restriction (member_id, restriction_type, starts_at DESC, ends_at);
