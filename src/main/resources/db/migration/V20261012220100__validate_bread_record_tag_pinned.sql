-- 앞 마이그레이션이 NOT VALID 로 건 제약을 기존 행에 대해 검증한다.
-- 따로 두는 이유: 검증은 쓰기를 막지 않는 약한 잠금으로 도는데, 제약 추가와 한 트랜잭션에 두면 강한 잠금이 끝까지 이어진다.
ALTER TABLE bread_record_tag VALIDATE CONSTRAINT chk_bread_record_tag_pinned;
