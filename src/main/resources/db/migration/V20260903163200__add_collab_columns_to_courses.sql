-- 협업 코스가 쓰는 courses 컬럼 네 개를 한 번에 넣는다. 전부 기본값·NULL 허용이라 미리 있어도 해가 없다.
--
-- day_count   : 빈 일차를 포함한 일차 수. 일행이 같은 일차 수를 보게 서버가 갖는다. 늘어나기만 한다.
-- visibility  : PUBLIC / UNLISTED / PRIVATE. 링크 공개(UNLISTED)가 생기기 전까지는 is_public 과 같은 값을 유지한다.
-- share_token : UNLISTED 로 처음 바꿀 때 발급하는 공유 토큰. 초대 토큰과 유출 피해 등급이 달라 컬럼을 분리한다.
-- version     : 폴링 ETag. 코스·가게·메모·멤버를 바꾸는 모든 쓰기에서 +1. JPA @Version 이 아니다(전면 낙관적 락이 된다).
--
-- is_public 은 지우지 않는다 — 구버전 앱이 읽는다. 다음 릴리즈에서 drop.
ALTER TABLE courses ADD COLUMN day_count   INTEGER     NOT NULL DEFAULT 1;
ALTER TABLE courses ADD COLUMN visibility  VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE courses ADD COLUMN share_token VARCHAR(64) UNIQUE;
ALTER TABLE courses ADD COLUMN version     BIGINT      NOT NULL DEFAULT 0;   -- 폴링 ETag. 쓰기마다 +1

UPDATE courses SET visibility = CASE WHEN is_public THEN 'PUBLIC' ELSE 'PRIVATE' END;
UPDATE courses c
SET day_count = GREATEST(1, COALESCE(
    (SELECT MAX(day) FROM course_stores s WHERE s.course_id = c.id AND s.deleted_at IS NULL), 1));
