-- 코스 좋아요는 찜(saved_courses)으로 대체 — 배선된 적 없는 초기 설계 잔재 제거
DROP TABLE IF EXISTS course_likes;
ALTER TABLE courses DROP COLUMN IF EXISTS like_count;
