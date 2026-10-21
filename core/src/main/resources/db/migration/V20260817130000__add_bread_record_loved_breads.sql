-- "최고였던 빵"을 여러 개 고를 수 있게 한다. 기존 단일 컬럼(loved_bread)은 구버전 앱 호환용으로
-- 남기고 첫 번째 값을 계속 채운다. 기존 값은 새 테이블로 옮긴다.
CREATE TABLE bread_record_loved_bread (
    record_id  BIGINT       NOT NULL REFERENCES bread_record (id) ON DELETE CASCADE,
    sort_order INTEGER      NOT NULL,
    name       VARCHAR(100) NOT NULL,
    PRIMARY KEY (record_id, sort_order)
);

INSERT INTO bread_record_loved_bread (record_id, sort_order, name)
SELECT id, 0, loved_bread FROM bread_record WHERE loved_bread IS NOT NULL;
