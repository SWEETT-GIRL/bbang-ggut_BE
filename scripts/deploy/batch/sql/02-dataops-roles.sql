-- 새 dataops datasource에서 sweetgirl 관리자로 실행한다.
-- 이름이 이미 있으면 자동 재사용/권한 변경하지 않고 실패한다.
-- 역할은 비밀번호 미설정 상태다. 비밀번호를 레포에 넣지 않는다.
-- 생성 후 IntelliJ에서는 ALTER ROLE <역할명> PASSWORD '<본인이 정한 비밀번호>'; 로 설정한다.
-- psql 터미널이라면 \password dataops_migrator / \password dataops_worker를 쓸 수 있다.
-- 예전 02 SQL로 dataops_service를 이미 만들었다면 03-rename-dataops-worker.sql을 대신 사용한다.
DO $$
BEGIN
    IF current_database() <> 'dataops' THEN
        RAISE EXCEPTION 'Connect to database dataops before provisioning roles';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dataops_service') THEN
        RAISE EXCEPTION 'Legacy dataops_service exists; verify its owner/use and use 03-rename-dataops-worker.sql only for the previous batch role';
    END IF;
    CREATE ROLE dataops_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    CREATE ROLE dataops_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    -- RDS 관리자는 PostgreSQL superuser가 아니다. 다른 역할의 기본 권한을 설정할 수 있게 명시한다.
    GRANT dataops_migrator TO sweetgirl;
    REVOKE CONNECT ON DATABASE dataops FROM PUBLIC;
    GRANT CONNECT ON DATABASE dataops TO dataops_migrator, dataops_worker;
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;
    GRANT USAGE, CREATE ON SCHEMA public TO dataops_migrator;
    GRANT USAGE ON SCHEMA public TO dataops_worker;
    ALTER DEFAULT PRIVILEGES FOR ROLE dataops_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dataops_worker;
    ALTER DEFAULT PRIVILEGES FOR ROLE dataops_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO dataops_worker;
END $$;

-- soboro의 테이블/스키마 권한과 기존 role은 변경하지 않는다.
-- 마이그레이션은 dataops_migrator로 수행해야 소유권·default privileges가 일치한다.
