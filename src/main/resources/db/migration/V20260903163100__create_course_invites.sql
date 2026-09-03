-- 코스 초대 링크. 토큰을 아는 사람이 수락하면 편집자가 된다.
--
-- "코스당 활성 링크 1개"는 DB 제약이 아니라 락 안의 서비스 로직으로 보장한다
-- (expires_at > now() 는 부분 인덱스 조건에 쓸 수 없다).
-- 하드 삭제 없음. 만료는 expires_at 으로 판정하고, 재생성으로 무효화된 행은 deleted_at 으로 표시한다.
CREATE TABLE course_invites (
    id          BIGSERIAL   PRIMARY KEY,
    course_id   BIGINT      NOT NULL REFERENCES courses (id),
    token       VARCHAR(64) NOT NULL UNIQUE,   -- SecureRandom 32B → base64url(43자)
    created_by  BIGINT      NOT NULL REFERENCES member (id),
    expires_at  TIMESTAMP   NOT NULL,          -- 생성 + 7일
    created_at  TIMESTAMP   NOT NULL,
    deleted_at  TIMESTAMP                      -- 재생성으로 무효화 = soft delete
);

-- 활성 링크 조회용
CREATE INDEX idx_course_invites_course_active ON course_invites (course_id) WHERE deleted_at IS NULL;
