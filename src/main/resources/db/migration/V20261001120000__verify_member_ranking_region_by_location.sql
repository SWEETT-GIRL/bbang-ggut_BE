-- 빵로그 랭킹 "내 지역"을 직접 고르는 시·도에서 지금 있는 곳으로 인증한 시·군·구로 바꾼다.
-- 위치로만 인증하니 다른 판을 보려면 실제로 거기 가 있어야 한다 — 재인증 잠금을 없애서 바꾼 횟수는 쓸 데가 없다.
ALTER TABLE member_ranking_region
	ADD COLUMN IF NOT EXISTS sigungu VARCHAR(20);   -- "동대문구"·"수원시" — bread_record.region_key 둘째 칸과 같은 표기. 시·도만 고르던 때의 행은 NULL(앱이 다시 인증하게 한다)

-- 마지막으로 인증한 시각(Asia/Seoul). RENAME 은 IF EXISTS 가 없어 두 번 돌아도 안전하게 감싼다.
DO $$
BEGIN
	IF EXISTS (SELECT 1 FROM information_schema.columns
		WHERE table_name = 'member_ranking_region' AND column_name = 'changed_at') THEN
		ALTER TABLE member_ranking_region RENAME COLUMN changed_at TO verified_at;
	END IF;
END $$;

ALTER TABLE member_ranking_region
	DROP COLUMN IF EXISTS change_count;
