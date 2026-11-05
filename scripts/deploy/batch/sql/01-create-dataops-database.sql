-- IntelliJ: 기존 soboro datasource, 관리자 sweetgirl, Auto-commit ON.
-- 문장을 하나씩 실행한다. CREATE DATABASE는 트랜잭션 안에서 실행할 수 없다.
-- 이미 존재하면 중단하고 소유자/내용을 먼저 확인한다.
SELECT current_database(), current_user, rolcreatedb
FROM pg_roles WHERE rolname = current_user;

CREATE DATABASE dataops OWNER sweetgirl TEMPLATE template0 ENCODING 'UTF8';

SELECT datname, pg_get_userbyid(datdba) AS owner
FROM pg_database WHERE datname = 'dataops';
