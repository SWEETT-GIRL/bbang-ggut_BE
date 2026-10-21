-- 코스 가게를 일차(day)로 묶는다. 기존 데이터는 전부 1일차로 둔다.
ALTER TABLE course_stores ADD COLUMN day INTEGER NOT NULL DEFAULT 1;
