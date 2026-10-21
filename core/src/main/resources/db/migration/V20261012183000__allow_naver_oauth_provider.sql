-- OAuthProvider 에 NAVER 추가 (#903)
--
-- oauth 테이블은 Flyway 이전에 Hibernate 가 만들어, provider 컬럼에 enum 값 CHECK 제약
-- (KAKAO, APPLE)이 걸려 있다. 그대로면 네이버 첫 가입의 INSERT 가 제약 위반으로 실패하고,
-- 웹 OAuth 콜백이 500 → /error → 401(COMMON401) 로 떨어진다.
--
-- 제약 이름이 환경마다 다를 수 있어 provider 를 거는 CHECK 를 찾아 지우고 다시 건다.
-- 제약이 없는 환경(테스트·새 로컬)에서도 그대로 통과한다.
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'oauth'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%provider%'
    LOOP
        EXECUTE format('ALTER TABLE oauth DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE oauth ADD CONSTRAINT oauth_provider_check
    CHECK (provider::text = ANY (ARRAY['KAKAO', 'APPLE', 'NAVER']::text[]));
