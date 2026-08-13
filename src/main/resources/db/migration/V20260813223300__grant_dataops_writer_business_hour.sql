-- 영업시간 백필 권한 (협의 안건 ⑤-1) — dataops 는 가게 단위 세트 교체(DELETE 후 요일 7행 INSERT)
-- 네이버 영업시간 보유율 74.3% → 운영 커버리지 214곳(0.5%)에서 ~6,800곳으로 확대하는 트랙.
-- 롤이 없는 환경(로컬·CI)에서는 조용히 건너뛴다.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_writer') THEN
        GRANT INSERT, DELETE ON store_business_hour TO dataops_writer;
    END IF;
END $$;
