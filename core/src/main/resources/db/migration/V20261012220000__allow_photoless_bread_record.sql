-- 사진 없는 빵 기록을 받는다(리워드 2.0 — 가게만 고르면 기록이 된다).
--
-- 사진에 안 붙은 빵 이름은 태그의 photo_id·x·y 를 비워 담는다. 셋은 함께 있거나 함께 비어야 한다 —
-- 사진은 있는데 좌표가 없는 태그는 화면에 그릴 수 없다.
ALTER TABLE bread_record_tag ALTER COLUMN photo_id DROP NOT NULL;
ALTER TABLE bread_record_tag ALTER COLUMN x DROP NOT NULL;
ALTER TABLE bread_record_tag ALTER COLUMN y DROP NOT NULL;

-- NOT VALID 로 먼저 건다 — 기존 행을 훑느라 표를 오래 잠그지 않는다. 검증은 다음 마이그레이션이 따로 한다.
ALTER TABLE bread_record_tag
    ADD CONSTRAINT chk_bread_record_tag_pinned CHECK (num_nulls(photo_id, x, y) IN (0, 3)) NOT VALID;

-- 같은 저장 요청의 재전송을 가린다. 앱이 저장 요청마다 번호를 붙이고, 응답을 못 받아 다시 보내도 기록이 둘 생기지 않는다.
-- 번호를 안 보내는 옛 앱의 기록은 비어 있다.
ALTER TABLE bread_record ADD COLUMN client_request_id UUID;
CREATE UNIQUE INDEX uq_bread_record_client_request
    ON bread_record (member_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
