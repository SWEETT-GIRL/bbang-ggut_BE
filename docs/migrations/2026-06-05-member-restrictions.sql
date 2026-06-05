-- 2026-06-05 — 회원 프로필 초기화 및 작성 제한 저장 구조 추가
-- 적용 대상: dev DB (soboro @ 3.27.115.144)
--
-- 사용처:
--   - POST /api/admin/members/{memberId}/profile-reset
--   - POST /api/admin/members/{memberId}/restrictions
--   - POST /menus/{menuId}/reviews
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-06-05-member-restrictions.sql
--   또는 DBeaver에서 아래 SQL을 직접 실행한다.

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
