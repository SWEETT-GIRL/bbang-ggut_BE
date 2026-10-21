-- 등록 후속 상태 (v3 P1-3) — "운영 store 가 생겼다"와 "등록이 끝났다"를 분리한다.
-- 이전에는 기존 placeId 로 귀속한 후보를 곧바로 REGISTERED 로 닫아, 그 가게에 sync_state 가 없어도
-- 아무 흔적이 남지 않았다 (로컬 실측 4건: 프롬에이프릴·꽃피앙·꼬팡케이크·샛별제과 — 한 번도 수집되지 않았다).

ALTER TABLE new_store_candidate ADD COLUMN IF NOT EXISTS registration_state VARCHAR(30);
-- 운영 INSERT 이전에 발급하는 최소 operation 식별자 — 결과 불명(UNKNOWN)을 확인 없이 다시 쓰지 않기 위한 표지
ALTER TABLE new_store_candidate ADD COLUMN IF NOT EXISTS registration_operation_id UUID;
ALTER TABLE new_store_candidate ADD COLUMN IF NOT EXISTS registration_note VARCHAR(300);
-- 위치 근거가 검증됐는지 — 기존 3토큰 도출은 검증이 아니다 (P4-4 공통 resolver 전까지 FALSE 로 남는다)
ALTER TABLE new_store_candidate ADD COLUMN IF NOT EXISTS location_evidence_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- 백필: 이미 REGISTERED 인 후보 중 sync_state(NAVER)가 있는 것만 COMPLETED. 없으면 복구 필요다.
-- location_evidence_verified 는 전부 FALSE 로 남긴다 — 이미 만든 가게의 후속을 복구하는 것과
-- 그 가게의 위치 품질을 검증 완료로 선언하는 것은 다른 일이다.
UPDATE new_store_candidate c SET registration_state = CASE
    WHEN c.status <> 'REGISTERED' THEN NULL
    WHEN c.registered_store_id IS NULL THEN 'NEEDS_RECOVERY'
    WHEN EXISTS (SELECT 1 FROM store_sync_state s
                 WHERE s.store_id = c.registered_store_id AND s.source = 'NAVER') THEN 'COMPLETED'
    ELSE 'NEEDS_RECOVERY' END
WHERE c.registration_state IS NULL;

CREATE INDEX IF NOT EXISTS idx_candidate_registration_state
    ON new_store_candidate (registration_state) WHERE registration_state <> 'COMPLETED';
