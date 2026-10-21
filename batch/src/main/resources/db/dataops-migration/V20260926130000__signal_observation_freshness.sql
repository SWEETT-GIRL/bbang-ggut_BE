-- 신호 최신성과 해시 버전 (v3 P2-1/P2-2)
--
-- 두 결함을 고치기 위한 additive 컬럼이다.
-- ① content hash 입력에 주소·좌표가 없어, 이름·전화·메뉴·영업시간이 그대로인 채 이전(移轉)한 가게는
--    해시가 같아 스냅샷이 저장되지 않고 재판정도 돌지 않았다 — 이전 자체가 파이프라인에 보이지 않았다.
-- ② 해시가 같은 방문은 아무것도 남기지 않고 끝나, 그 방문이 가져온 리뷰·신호 변화가 버려졌다
--    (로컬 실측: NAVER 총 방문 126,659 중 무변경 22,013건 = 17.4%). store_signal 은 수동 rebuild 로만 갱신됐다.

-- 해시 세대 — 기존 행은 주소·좌표가 빠진 1세대다. 세대가 다르면 비교에 legacy 해시를 쓰고,
-- 같은 값이면 카운터를 올리지 않고 조용히 2세대로 올린다 (버전 전환을 가게 변경률에 합산하지 않는다).
ALTER TABLE store_sync_state ADD COLUMN IF NOT EXISTS content_hash_version SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE place_snapshot ADD COLUMN IF NOT EXISTS content_hash_version SMALLINT NOT NULL DEFAULT 1;

-- 신호의 근거를 스냅샷에서 관측으로 옮긴다. signal_observed_at 은 last_success_at 과 다르다:
-- 수집에 성공한 시각이 아니라, 이 신호 값을 실제로 본 관측의 시각이다.
ALTER TABLE store_signal ADD COLUMN IF NOT EXISTS signal_hash CHAR(64);
ALTER TABLE store_signal ADD COLUMN IF NOT EXISTS signal_observed_at TIMESTAMP;
ALTER TABLE store_signal ADD COLUMN IF NOT EXISTS observation_id VARCHAR(64);
-- 이 신호를 만든 원본이 place_snapshot 에 남아 있는지. false 면 파서가 바뀌어도 재파싱할 수 없으므로
-- 재수집 대상이다 — 낡은 스냅샷으로 되돌리지 않는다.
ALTER TABLE store_signal ADD COLUMN IF NOT EXISTS payload_retained BOOLEAN NOT NULL DEFAULT TRUE;

-- 기존 행은 전부 스냅샷에서 나왔다. 관측 시각은 그 스냅샷의 수집 시각으로 둔다.
-- signal_hash 는 파서를 돌려야 나오므로 NULL 로 남긴다 — NULL 은 어떤 해시와도 같지 않아
-- 다음 관측이 반드시 신호를 갱신한다(놓친 갱신을 만들지 않는 쪽으로 기운다).
UPDATE store_signal SET signal_observed_at = fetched_at WHERE signal_observed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_store_signal_staleness ON store_signal (parser_version, signal_observed_at);
