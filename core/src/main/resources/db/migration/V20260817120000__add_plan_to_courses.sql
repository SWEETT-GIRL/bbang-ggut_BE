-- 빵투어를 만들 때 정한 여행 계획: 어느 지역을 언제 가는지.
-- 실제로 다녀왔는지(빵로그 기록)와 대조하려고 받는다. 구버전 앱은 안 보내므로 전부 nullable.
ALTER TABLE courses ADD COLUMN region VARCHAR(20);
ALTER TABLE courses ADD COLUMN start_date DATE;
ALTER TABLE courses ADD COLUMN end_date DATE;
