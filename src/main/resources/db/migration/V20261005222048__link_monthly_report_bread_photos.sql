-- 기존 보고서는 당시 사진 키로 출처가 유일하게 확인되는 경우에만 연결한다.
-- 삭제됐거나 여러 로그에서 공유한 키는 추측하지 않고 기존 키 확인 방식으로 남긴다.
WITH matches AS (
    SELECT report.member_id, report.year_month, bread->>'name' AS category,
           min(photo.record_id) AS record_id, min(photo.reference_id::text) AS reference_id
    FROM monthly_report_member report
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(report.payload->'breads') = 'array' THEN report.payload->'breads' ELSE '[]'::jsonb END
    ) bread
    JOIN bread_record_photo photo ON bread->>'imageKey' IN
        (photo.aws_key, photo.resized_key_320, photo.resized_key_640, photo.resized_key_1024)
    JOIN bread_record record ON record.id = photo.record_id
        AND record.member_id = report.member_id AND record.deleted_at IS NULL
    WHERE report.status = 'PUBLISHED' AND NOT (report.raw_metrics ? 'breadPhotoSources')
        AND length(trim(bread->>'name')) > 0
    GROUP BY report.member_id, report.year_month, bread->>'name'
    HAVING count(DISTINCT photo.reference_id) = 1
), sources AS (
    SELECT member_id, year_month,
           jsonb_object_agg(category,
               jsonb_build_object('recordId', record_id, 'photoReferenceId', reference_id)) AS refs
    FROM matches
    GROUP BY member_id, year_month
)
UPDATE monthly_report_member report
SET raw_metrics = jsonb_set(report.raw_metrics, '{breadPhotoSources}', sources.refs)
FROM sources
WHERE report.member_id = sources.member_id AND report.year_month = sources.year_month;
