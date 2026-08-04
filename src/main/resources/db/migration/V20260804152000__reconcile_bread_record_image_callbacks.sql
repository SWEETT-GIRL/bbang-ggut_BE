-- 이미지 row 생성과 Lambda 콜백이 겹쳐 보류 상태로 남은 빵 기록 사진을 복구한다.
-- GIF는 리사이즈본이 만들어지지 않으므로(applyStatus와 같은 규칙) 키를 채우지 않는다.
UPDATE bread_record_photo photo
SET processing_status = callback.status,
    resized_key_320 = CASE
        WHEN callback.status = 'DONE' AND photo.mime_type <> 'IMAGE_GIF'
            THEN 'resized/320/' || photo.aws_key
        ELSE NULL
    END,
    resized_key_640 = CASE
        WHEN callback.status = 'DONE' AND photo.mime_type <> 'IMAGE_GIF'
            THEN 'resized/640/' || photo.aws_key
        ELSE NULL
    END,
    resized_key_1024 = CASE
        WHEN callback.status = 'DONE' AND photo.mime_type <> 'IMAGE_GIF'
            THEN 'resized/1024/' || photo.aws_key
        ELSE NULL
    END,
    updated_at = CURRENT_TIMESTAMP
FROM image_processing_callback callback
WHERE callback.aws_key = photo.aws_key;

DELETE FROM image_processing_callback callback
WHERE EXISTS (
    SELECT 1
    FROM bread_record_photo photo
    WHERE photo.aws_key = callback.aws_key
);
