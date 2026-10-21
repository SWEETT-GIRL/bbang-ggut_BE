-- "이 집 또 갈래요" — 기록 단위의 재방문 의사. 2.0 앱이 기록·고치기 화면의 스위치 값을 보낸다.
-- 안 보낸 기록(구버전 앱·옛 기록)은 NULL 이고, 그때는 앱이 "빵 하나라도 또 먹을래요면 또 갈래요"로 추정해 보여 준다.
ALTER TABLE bread_record ADD COLUMN revisit BOOLEAN;
