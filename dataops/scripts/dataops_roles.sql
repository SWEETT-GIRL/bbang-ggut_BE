-- dataops 전용 DB 계정 복구/생성 SQL (마스터 계정으로 실행)
--
-- 배경: RDS 인스턴스를 dump/restore 로 재생성하면 role 은 클러스터 수준 객체라
-- 함께 소실되고, 테이블에 걸려 있던 해당 role 의 GRANT(ACL)도 복원 시 스킵된다.
-- (2026-08 인스턴스 사양 변경 때 실제 발생 — 이 파일이 박제본)
--
-- 실행 전 치환:
--   <READER_PW> / <WRITER_PW>  → 각 폴더 .env 의 PROD_DB_PASSWORD 값
--   <APP_OWNER>                → 본 앱(스프링)이 쓰는 테이블 소유 계정명
--
-- 근거 문서: data-freshness-design.md §쓰기 표면 최소화 (writer 는 store·menu
-- INSERT/UPDATE + 전역 SELECT, DELETE 는 계정 권한 자체가 없음),
-- 커밋 07346d7 (alias INSERT·business_hour INSERT/DELETE GRANT)

-- ── 읽기 전용 (분석·추출·검증: localdata 트랙 등) ─────────────────────────
CREATE ROLE dataops_reader LOGIN PASSWORD '<READER_PW>';
GRANT CONNECT ON DATABASE soboro TO dataops_reader;
GRANT USAGE ON SCHEMA public TO dataops_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO dataops_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE <APP_OWNER> IN SCHEMA public
  GRANT SELECT ON TABLES TO dataops_reader;

-- ── 쓰기 전용 (dataops 서버 ServingWriter 경로 — 2단계 운영 반영) ─────────
CREATE ROLE dataops_writer LOGIN PASSWORD '<WRITER_PW>';
GRANT CONNECT ON DATABASE soboro TO dataops_writer;
GRANT USAGE ON SCHEMA public TO dataops_writer;
-- 전역 SELECT (diff 계산·선점 확인용)
GRANT SELECT ON ALL TABLES IN SCHEMA public TO dataops_writer;
ALTER DEFAULT PRIVILEGES FOR ROLE <APP_OWNER> IN SCHEMA public
  GRANT SELECT ON TABLES TO dataops_writer;
-- 쓰기 표면 (DELETE 없음 — store_business_hour 만 세트 원자 교체용 예외)
GRANT INSERT, UPDATE ON store TO dataops_writer;
GRANT INSERT, UPDATE ON menu TO dataops_writer;
GRANT INSERT ON store_name_alias TO dataops_writer;
GRANT INSERT, DELETE ON store_business_hour TO dataops_writer;
-- serial 시퀀스 사용 (identity 컬럼이면 불필요하나 무해)
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO dataops_writer;

-- 확인:
--   SELECT rolname FROM pg_roles WHERE rolname LIKE 'dataops%';
--   SELECT grantee, table_name, privilege_type FROM information_schema.role_table_grants
--    WHERE grantee LIKE 'dataops%' ORDER BY 1, 2, 3;
