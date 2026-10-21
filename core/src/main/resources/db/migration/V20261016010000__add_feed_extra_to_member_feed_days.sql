-- 밥 룰렛 — 하루 1 번 무료 뒤 광고를 끝까지 보면 한 판 더(하루 3 번까지).
-- 그날 행(PK 가 회원·한국 날짜)에 두니 자정 초기화는 doneToday 와 같은 기준이다 — 새 날은 새 행이고, 상한으로 다시 시작한다.
-- 행은 무료 판을 돌릴 때 생긴다. 남은 횟수는 그때 feed.ad-daily-cap 값으로 넣고, DEFAULT 3 은 그 전에 생긴 오늘 행을 채우는 값이다.
ALTER TABLE member_feed_days ADD COLUMN feed_extra_left INTEGER NOT NULL DEFAULT 3;    -- 오늘 광고를 보고 더 돌릴 수 있는 남은 횟수
ALTER TABLE member_feed_days ADD COLUMN feed_extra_ready BOOLEAN NOT NULL DEFAULT FALSE; -- 광고를 끝까지 봐서 한 판이 열려 있다. 돌리면 다시 FALSE
