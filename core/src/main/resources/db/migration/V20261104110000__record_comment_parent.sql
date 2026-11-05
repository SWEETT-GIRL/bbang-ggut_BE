-- 댓글 답글(2026-11-04). 깊이는 1 — 답글의 답글은 같은 부모에 붙는다. 비어 있으면 최상위 댓글.
-- 목록은 그대로 평면 · 오래된 순으로 내리고 앱이 parent_comment_id 로 묶는다(구버전 앱은 평면으로 본다).
ALTER TABLE record_comment ADD COLUMN parent_comment_id BIGINT REFERENCES record_comment (id);

CREATE INDEX idx_record_comment_parent ON record_comment (parent_comment_id)
    WHERE parent_comment_id IS NOT NULL;
