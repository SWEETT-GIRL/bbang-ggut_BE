ALTER TABLE bread_record ADD COLUMN record_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE bread_record ADD COLUMN tutorial_template_code VARCHAR(60);
ALTER TABLE bread_record ADD CONSTRAINT ck_bread_record_type CHECK (record_type IN ('NORMAL', 'TUTORIAL'));
ALTER TABLE bread_record ADD CONSTRAINT ck_tutorial_record_private
    CHECK (record_type <> 'TUTORIAL' OR (store_id IS NULL AND is_public = FALSE));
CREATE UNIQUE INDEX uq_member_active_tutorial ON bread_record (member_id)
    WHERE record_type = 'TUTORIAL' AND deleted_at IS NULL;
CREATE TABLE member_tutorial_completion (
    member_id BIGINT PRIMARY KEY REFERENCES member(id),
    record_id BIGINT NOT NULL REFERENCES bread_record(id),
    template_code VARCHAR(60) NOT NULL,
    completed_at TIMESTAMP NOT NULL
);

-- 원천 이력에도 타입을 남겨 월말 재생에서 더미 기록이 실소비로 되살아나지 않게 한다.
CREATE OR REPLACE FUNCTION monthly_report_record_payload(r bread_record) RETURNS jsonb LANGUAGE sql VOLATILE AS $$
    SELECT jsonb_build_object('id', r.id, 'storeId', r.store_id, 'regionKey', r.region_key,
        'createdAt', r.created_at, 'eatenAt', r.eaten_at, 'deletedAt', r.deleted_at,
        'recordType', r.record_type,
        'lovedBread', r.loved_bread, 'lovedBreads', COALESCE(
            (SELECT jsonb_agg(name ORDER BY sort_order) FROM bread_record_loved_bread WHERE record_id = r.id),
            '[]'::jsonb));
$$;
