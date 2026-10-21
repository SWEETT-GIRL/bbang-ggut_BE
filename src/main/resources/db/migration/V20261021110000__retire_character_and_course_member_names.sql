-- 팔로우 · 커뮤니티 (2026-10-21): 사람 이름은 회원 닉네임 하나로 통일한다.
-- 1) 친구(캐릭터)에게 지어 준 이름을 비운다. 컬럼은 설치된 앱이 응답 필드를 읽어서 남긴다 — 값은 늘 NULL.
-- 2) 빵투어 안 이름(course_members.display_name)은 더 쓰지 않는다. 새 행은 비우므로 NOT NULL 을 푼다.
--    두 컬럼 삭제는 앱이 다 올라온 뒤 별도 마이그레이션.

UPDATE member_characters SET nickname = NULL WHERE nickname IS NOT NULL;

ALTER TABLE course_members ALTER COLUMN display_name DROP NOT NULL;
