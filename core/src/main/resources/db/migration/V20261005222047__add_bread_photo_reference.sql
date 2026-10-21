ALTER TABLE bread_record_photo
    ADD COLUMN reference_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX uq_bread_record_photo_reference ON bread_record_photo (reference_id);

COMMENT ON COLUMN bread_record_photo.reference_id IS
    '사진 교체와 행 재생성에도 유지하는 식별자. 삭제 후 새로 추가하는 사진은 새 값을 사용한다';
