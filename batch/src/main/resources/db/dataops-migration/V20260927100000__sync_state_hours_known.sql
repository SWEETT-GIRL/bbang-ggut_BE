-- 정기 영업시간 기억 (v3 P2 · docs/hours-change-detection-design.md 3-2)
--
-- 네이버 영업시간은 오늘부터 7일을 보여 주고, 임시 일정이 있는 날은 "토(9/26)"처럼 날짜를 붙여 그날 값을 담는다.
-- 해시가 그 표시를 세면 매일 방문의 약 95%가 가짜 "변경"이었다(표본 2차 61곳 중 58곳). 해시 3세대는 영업시간을 빼고,
-- 영업시간 변경은 여기 기억한 요일별 정기 값과 이번 관측의 날짜 없는 요일을 공통 요일끼리 비교해 판정한다.
--
-- 모양: {"MONDAY": {"open": "09:00", "close": "18:00", "closed": false, "observedAt": "2026-09-27T09:48:28"}, ...}
-- NULL = 아직 추적 전(3세대 전환 전), {} = 추적 중이나 정기 요일을 아직 못 봄.
-- 운영 store_business_hour 가 아니라 네이버에서 마지막으로 본 정기 값이다 — 운영 대조는 재판정의 일이다.
ALTER TABLE store_sync_state ADD COLUMN IF NOT EXISTS hours_known JSONB;
