-- 블루그린 배포 겹침 구간 보호: 새 컨테이너가 V20261021140000(nickname_normalized NOT NULL)을 적용한 뒤에도
-- 옛 컨테이너(그 컬럼을 모르는 코드)가 잠깐 더 돌며 회원을 INSERT 한다(게스트 발급 · 소셜 가입). 그때 NOT NULL 에 걸려
-- 로그인이 실패하지 않게 DB 가 닉네임에서 정규화 값을 채운다. 새 코드는 늘 직접 채우므로 평소엔 아무것도 안 한다.
CREATE OR REPLACE FUNCTION member_fill_nickname_normalized() RETURNS trigger AS $$
BEGIN
    IF NEW.nickname_normalized IS NULL
        OR (TG_OP = 'UPDATE' AND NEW.nickname IS DISTINCT FROM OLD.nickname
            AND NEW.nickname_normalized IS NOT DISTINCT FROM OLD.nickname_normalized) THEN
        NEW.nickname_normalized := lower(regexp_replace(normalize(NEW.nickname, NFC), '\s', '', 'g'));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_member_fill_nickname_normalized ON member;
CREATE TRIGGER trg_member_fill_nickname_normalized
    BEFORE INSERT OR UPDATE OF nickname, nickname_normalized ON member
    FOR EACH ROW EXECUTE FUNCTION member_fill_nickname_normalized();
