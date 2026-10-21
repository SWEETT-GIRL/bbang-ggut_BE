-- 빵 랭킹 조회 인덱스 보강.

-- 1. 가게별 빵로그(비공개 포함) — 빵집 판 집계·가게 상세 평가가 store_id 로 모든 기록을 읽는다.
--    기존 idx_bread_record_store_public 은 공개 기록만 덮어 여기엔 못 쓴다.
CREATE INDEX IF NOT EXISTS idx_bread_record_store_active
	ON bread_record (store_id)
	WHERE deleted_at IS NULL AND store_id IS NOT NULL;

-- 2. uq_bread_rank_entry (member_id, category_id, store_id) 의 앞 두 칸이 같은 역할 — 겹치는 인덱스는 뺀다.
DROP INDEX IF EXISTS idx_bread_rank_entry_member;

-- 3. 내 지역 판 필터(split_part 로 시·도·시·군·구를 뽑는 표현식) — 스냅샷이 매일 구마다 돌리기 시작해 비용이 곱해졌다.
CREATE INDEX IF NOT EXISTS idx_bread_record_region_sido_sigungu
	ON bread_record ((split_part(region_key, '_', 1)), (split_part(region_key, '_', 2)))
	WHERE deleted_at IS NULL;
