-- 빵로그 공개/비공개: 공개하면 가게 상세에 리뷰처럼 쌓인다. 옛 기록은 나만 본다고 생각하고 쓴 것이라 전부 비공개로 시작한다.
ALTER TABLE bread_record ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN bread_record.is_public IS '공개하면 가게 상세 빵로그 목록(GET /stores/{id}/bread-records)에 보인다. 기본 비공개';

-- 가게 상세 목록: 그 가게의 공개 · 삭제 안 된 기록을 먹은 날 최신순으로.
CREATE INDEX idx_bread_record_store_public ON bread_record (store_id, eaten_at DESC, id DESC)
    WHERE is_public AND deleted_at IS NULL;
