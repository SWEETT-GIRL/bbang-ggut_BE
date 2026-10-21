-- 재판정 범위 (v3 P1-2) — 전역 PENDING 삭제를 가게·producer·target family 단위 정리로 바꾸기 위한 additive 컬럼.
-- 전역 삭제는 네이버 스냅샷 재판정 한 번이 공공데이터 폐업 제안·매칭 제안까지 함께 날렸다.

ALTER TABLE change_proposal ADD COLUMN IF NOT EXISTS producer VARCHAR(40);
ALTER TABLE change_proposal ADD COLUMN IF NOT EXISTS target_family VARCHAR(30);
ALTER TABLE change_proposal ADD COLUMN IF NOT EXISTS policy_version VARCHAR(60);
ALTER TABLE change_proposal ADD COLUMN IF NOT EXISTS reconcile_run_id UUID;

-- target_family 는 target 에서 결정적으로 나오므로 그대로 채운다 (ProposalTargetFamily.of 와 같은 규칙).
UPDATE change_proposal SET target_family = CASE
    WHEN target = 'store.name'             THEN 'STORE_NAME'
    WHEN target = 'store.phone'            THEN 'STORE_PHONE'
    WHEN target = 'store.instagram_handle' THEN 'STORE_INSTAGRAM'
    WHEN target = 'store.address'          THEN 'STORE_ADDRESS'
    WHEN target = 'store.region'           THEN 'STORE_REGION'
    WHEN target = 'store.status'           THEN 'STORE_STATUS'
    WHEN target = 'store.category'         THEN 'STORE_CATEGORY'
    WHEN target = 'store.naver_place_id'   THEN 'STORE_NAVER_PLACE_ID'
    WHEN target = 'store.business_hours'   THEN 'BUSINESS_HOURS'
    WHEN target LIKE 'menu.add.%'          THEN 'MENU_CONTENT'
    WHEN target ~ '^menu\.\d+\.price$'       THEN 'MENU_CONTENT'
    WHEN target ~ '^menu\.\d+\.description$' THEN 'MENU_CONTENT'
    WHEN target ~ '^menu\.\d+\.removed$'     THEN 'MENU_REMOVAL'
    WHEN target ~ '^menu\.\d+\.category$'    THEN 'MENU_CATEGORY'
    ELSE 'UNCLASSIFIED' END
WHERE target_family IS NULL;

-- producer 는 지어내지 않는다. (target, source) 조합이 코드 전수 확인상 생성자와 1:1 인 것만 채우고
-- 나머지는 LEGACY_UNKNOWN 으로 둔다. 생성자 목록(2026-09-26 확인):
--   ReconcileService                 → source NAVER + 이름/전화/인스타/주소/영업시간/메뉴 대상
--   localdata_status_delta/enqueue/dup_cleanup → source LOCALDATA + store.status
--   region_recompute.py              → source KAKAO + store.region
--   store_category_propose.py        → source NAVER + store.category
--   MatchingCommandService           → store.naver_place_id
UPDATE change_proposal SET producer = CASE
    WHEN target = 'store.status'        AND source = 'LOCALDATA' THEN 'LOCALDATA_STATUS'
    WHEN target = 'store.region'        AND source = 'KAKAO'     THEN 'REGION_RECOMPUTE'
    WHEN target = 'store.category'      AND source = 'NAVER'     THEN 'STORE_CATEGORY_PROPOSE'
    WHEN target = 'store.naver_place_id'                         THEN 'NAVER_MATCHING'
    WHEN source = 'NAVER' AND target_family IN ('STORE_NAME', 'STORE_PHONE', 'STORE_INSTAGRAM',
        'STORE_ADDRESS', 'BUSINESS_HOURS', 'MENU_CONTENT', 'MENU_REMOVAL', 'MENU_CATEGORY')
        THEN 'NAVER_SNAPSHOT_RECONCILE'
    ELSE 'LEGACY_UNKNOWN' END
WHERE producer IS NULL;

ALTER TABLE change_proposal ALTER COLUMN producer SET DEFAULT 'LEGACY_UNKNOWN';
ALTER TABLE change_proposal ALTER COLUMN target_family SET DEFAULT 'UNCLASSIFIED';

-- 범위 정리 조회용 — (가게, producer, family) 의 PENDING 을 찾는다
CREATE INDEX IF NOT EXISTS idx_proposal_scope ON change_proposal (store_id, producer, target_family)
    WHERE status = 'PENDING';
