-- 2026-10-21 에 끝낸 이름 짓기 컬럼을 지운다. 값은 그때부터 늘 NULL 이었고,
-- 엔티티 매핑은 #997 로 먼저 지워 운영에 깔렸다(블루그린 겹침 동안 옛 컨테이너가 읽지 않도록).
ALTER TABLE course_members DROP COLUMN IF EXISTS display_name;
ALTER TABLE member_characters DROP COLUMN IF EXISTS nickname;
