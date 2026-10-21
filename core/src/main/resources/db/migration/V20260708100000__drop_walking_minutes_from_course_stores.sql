-- 가게 간 거리(m/km)는 FE가 좌표로 계산해 표시하는 것으로 확정 — 도보 시간 저장 폐지
ALTER TABLE course_stores DROP COLUMN IF EXISTS walking_minutes_to_next;
