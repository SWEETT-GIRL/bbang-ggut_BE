-- 핫플 alias 보강 (① 식별층): 상권 별칭/이형태 → 핫플 식별 recall 향상.
-- 역명형은 STATION이 커버하므로 제외. 확실·통용되는 별칭만.
-- 재실행 안전(ON CONFLICT DO NOTHING). 동음이의 없는 핫플명 대상.

INSERT INTO search_region_alias (region_id, alias, normalized_alias, is_primary)
SELECT hp.id, v.alias, v.alias, v.is_primary
FROM (VALUES
        ('홍대',         '홍익대',         FALSE),  -- 대학 풀네임 (홍대입구역=STATION)
        ('부산대',       '부산대학교',     FALSE),
        ('전대후문',     '전남대후문',     FALSE),
        ('광안리',       '광안리해수욕장', FALSE),  -- 정식 명칭
        ('해운대',       '해운대해수욕장', FALSE),
        ('센텀',         '센텀시티',       FALSE),
        ('경리단길',     '경리단',         FALSE),  -- 축약 통용
        ('가로수길',     '신사가로수길',   FALSE),
        ('경주황리단길', '황리단길',       FALSE),  -- 지역접두 분리
        ('수원역',       '수원역',         TRUE),   -- alias 누락 복구
        ('을지로',       '힙지로',         FALSE),  -- 속칭
        ('서래마을',     '서래',           FALSE)   -- 축약
    ) AS v(hp_name, alias, is_primary)
JOIN search_region hp ON hp.name = v.hp_name AND hp.type = 'HOT_PLACE'
ON CONFLICT ON CONSTRAINT uq_search_region_alias_region_alias DO NOTHING;
