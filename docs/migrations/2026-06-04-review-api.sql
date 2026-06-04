-- 2026-06-04 — 리뷰 핵심 API 저장 구조 추가
-- 적용 대상: dev DB (soboro @ 3.27.115.144)
--
-- 사용처:
--   - GET /menus/{menuId}/reviews/summary
--   - GET /menus/{menuId}/reviews
--   - POST /reviews/images/presigned-urls
--   - POST /menus/{menuId}/reviews
--
-- 적용 방법:
--   psql -h 3.27.115.144 -U sweetgirl -d soboro -f docs/migrations/2026-06-04-review-api.sql
--   또는: scripts/.venv/bin/python scripts/apply_migration.py docs/migrations/2026-06-04-review-api.sql
--
-- 적용 후 backend 재기동 — local 프로파일 ddl-auto=validate 통과 확인.

CREATE TABLE IF NOT EXISTS review (
    id BIGSERIAL PRIMARY KEY,
    menu_id BIGINT NOT NULL REFERENCES menu (id),
    member_id BIGINT NOT NULL REFERENCES member (id),
    rating DECIMAL(2, 1) NOT NULL,
    repurchase_intent VARCHAR(50) NOT NULL,
    sweetness VARCHAR(50) NOT NULL,
    filling_amount VARCHAR(50) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    like_count INTEGER NOT NULL DEFAULT 0,
    comment_count INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT ck_review_rating_range CHECK (rating >= 0.5 AND rating <= 5.0),
    CONSTRAINT ck_review_rating_half_step CHECK (mod((rating * 10)::INTEGER, 5) = 0)
);

CREATE TABLE IF NOT EXISTS review_texture_tag (
    review_id BIGINT NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    texture_tag VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS review_image_file (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(50) NOT NULL,
    aws_key VARCHAR(255) NOT NULL,
    display_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS review_like (
    id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES member (id),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uk_review_like_member UNIQUE (review_id, member_id)
);

-- 한 회원은 같은 메뉴에 활성 리뷰를 하나만 작성할 수 있다.
-- soft delete 된 리뷰는 제외해 재작성 가능하게 둔다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_review_member_menu_active
    ON review (member_id, menu_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_menu_active_recent
    ON review (menu_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_menu_active_liked
    ON review (menu_id, like_count DESC, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_review_image_file_review
    ON review_image_file (review_id, display_order ASC, id ASC);

CREATE INDEX IF NOT EXISTS idx_review_like_member_review
    ON review_like (member_id, review_id);
