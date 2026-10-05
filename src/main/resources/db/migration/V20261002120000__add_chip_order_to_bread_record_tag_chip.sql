-- 빵 평가 칩(식감·맛)을 사용자가 고른 순서대로 돌려주기 위한 순서 칸 (#842).
-- 앱은 textures[0] 을 대표 식감으로 보여 주는데, 지금은 enum 선언 순서라 "쫄깃"만 두었다가 "바삭"을 더하면 대표가 바삭으로 바뀐다.
-- 기존 행은 0 — 읽을 때 chip_order 가 같으면 enum 순서로 가르므로 옛 평가는 지금과 똑같이 보인다.
ALTER TABLE bread_record_tag_chip ADD COLUMN IF NOT EXISTS chip_order INTEGER NOT NULL DEFAULT 0;
