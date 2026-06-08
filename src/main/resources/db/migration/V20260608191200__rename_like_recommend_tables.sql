-- menu recommend → menu like
ALTER TABLE recommend_menu RENAME TO menu_like;
ALTER TABLE menu_like RENAME CONSTRAINT uq_recommend_menu TO uq_menu_like;
ALTER TABLE menu RENAME COLUMN recommend_count TO like_count;

-- review like → review recommend
ALTER TABLE review_like RENAME TO review_recommend;
ALTER TABLE review_recommend RENAME CONSTRAINT uk_review_like_member TO uk_review_recommend_member;
ALTER TABLE review RENAME COLUMN like_count TO recommend_count;

-- review 정렬 인덱스 재생성 (like_count → recommend_count)
DROP INDEX IF EXISTS idx_review_menu_visible_liked;
CREATE INDEX idx_review_menu_visible_recommended
    ON review (menu_id, recommend_count DESC, created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND hidden_at IS NULL;
