-- 원자적 due 클레임과 attempt 세대 (v3 P2-1)
--
-- 기존 배부는 "조회 후 스탬프" 두 단계라, 워커 둘이 동시에 조회하면 같은 가게를 둘 다 받았다.
-- 지금은 워커를 한 번에 하나만 돌려 피하고 있을 뿐, 병렬 실행을 켜는 순간 같은 가게를 두 번 긁는다.
-- 한 문장 UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED) 로 바꿔 서로 겹치지 않게 한다.

-- 클레임 토큰 — 어느 배부에서 나간 항목인지. 같은 토큰의 제출만 그 배부의 결과로 인정한다
ALTER TABLE store_sync_state ADD COLUMN IF NOT EXISTS claim_token UUID;
-- attempt 세대 — 배부마다 1씩 오른다. 제출이 가진 세대가 현재보다 낮으면 늦게 도착한 이전 시도다
ALTER TABLE store_sync_state ADD COLUMN IF NOT EXISTS attempt_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE store_sync_state ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_sync_state_claim ON store_sync_state (claim_token) WHERE claim_token IS NOT NULL;
