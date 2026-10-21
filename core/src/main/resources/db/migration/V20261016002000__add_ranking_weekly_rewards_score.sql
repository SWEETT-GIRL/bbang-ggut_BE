-- 그 주의 점수(사진 있는 기록 수)를 보상 줄에 함께 남긴다 — 앱이 "지난주 빵 N개"를 보여 준다.
-- 점수를 남기기 전에 만든 줄은 NULL 이다.
ALTER TABLE ranking_weekly_rewards ADD COLUMN score INTEGER;
