-- 예전 02-dataops-roles.sql로 만든 배치 계정만 이름을 바꾼다. 최초 생성 시에는 실행하지 않는다.
-- dataops datasource의 sweetgirl 관리자 콘솔에서, 배치가 실행되지 않는 동안 적용한다.
-- 기존 agent 전용 provisioning의 동명 계정이면 이 스크립트를 사용하지 않는다.
-- 객체 소유권·GRANT·DEFAULT PRIVILEGES는 같은 role OID를 계속 참조한다.
DO $$
BEGIN
    IF current_database() <> 'dataops' THEN
        RAISE EXCEPTION 'Connect to database dataops before renaming the batch role';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dataops_service')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dataops_migrator') THEN
        RAISE EXCEPTION 'Previous batch roles must exist; use 02-dataops-roles.sql for initial provisioning';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dataops_worker') THEN
        RAISE EXCEPTION 'dataops_worker already exists; inspect roles instead of merging their permissions';
    END IF;
    ALTER ROLE dataops_service RENAME TO dataops_worker;
END $$;

-- 이름 변경 후 본인이 정한 비밀번호를 다시 설정한다. MD5 비밀번호는 이름 변경 시 제거된다.
-- ALTER ROLE dataops_worker PASSWORD '<본인이 정한 워커 비밀번호>';
-- Parameter Store DATAOPS_DB_USERNAME=dataops_worker, DATAOPS_DB_PASSWORD=위 비밀번호로 맞춘다.
