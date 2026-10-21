ALTER TABLE monthly_report_member
    ADD COLUMN character_code VARCHAR(100),
    ADD COLUMN character_name VARCHAR(100),
    ADD COLUMN character_image_key VARCHAR(2048),
    ADD COLUMN planet_code VARCHAR(100),
    ADD COLUMN planet_name VARCHAR(100);

-- 기존 발행본의 당시 값을 옮긴다. 현재 member_world/카탈로그로 과거 데이터를 채우지 않는다.
UPDATE monthly_report_member
SET character_code = payload->'character'->>'code',
    character_name = payload->'character'->>'name',
    character_image_key = payload->'character'->>'imageKey',
    planet_code = payload->'planet'->>'code',
    planet_name = payload->'planet'->>'name'
WHERE status = 'PUBLISHED';

-- JSON 최종본과 별도 스냅샷 열이 어긋난 발행은 거절한다. 그림 키는 없을 수 있다.
ALTER TABLE monthly_report_member
    ADD CONSTRAINT ck_monthly_report_appearance CHECK (
        status <> 'PUBLISHED' OR (
            character_code IS NOT NULL AND length(trim(character_code)) > 0
            AND character_name IS NOT NULL AND length(trim(character_name)) > 0
            AND planet_code IS NOT NULL AND length(trim(planet_code)) > 0
            AND planet_name IS NOT NULL AND length(trim(planet_name)) > 0
            AND character_code IS NOT DISTINCT FROM (payload->'character'->>'code')
            AND character_name IS NOT DISTINCT FROM (payload->'character'->>'name')
            AND character_image_key IS NOT DISTINCT FROM (payload->'character'->>'imageKey')
            AND planet_code IS NOT DISTINCT FROM (payload->'planet'->>'code')
            AND planet_name IS NOT DISTINCT FROM (payload->'planet'->>'name')
        )
    );

COMMENT ON COLUMN monthly_report_member.character_code IS '발행 당시 선택 캐릭터 코드 (현재 선택과 독립)';
COMMENT ON COLUMN monthly_report_member.character_name IS '발행 당시 캐릭터 표시 이름';
COMMENT ON COLUMN monthly_report_member.character_image_key IS '발행 당시 캐릭터 그림 키, 없으면 NULL';
COMMENT ON COLUMN monthly_report_member.planet_code IS '발행 당시 보고서의 행성 코드 (현재 진행도와 독립)';
COMMENT ON COLUMN monthly_report_member.planet_name IS '발행 당시 행성 표시 이름';
