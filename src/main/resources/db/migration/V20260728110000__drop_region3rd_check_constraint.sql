-- famous_menu_by_region.region3rd에 걸려 있던 CHECK 제약을 뗀다.
--
-- 이 제약은 마이그레이션이 만든 게 아니라 과거 ddl-auto=update 시절 Hibernate가
-- Region3rd enum 값을 나열해 만들어 둔 것이다(dev에만 남아 있고 prod에는 없다).
-- 값이 enum에 박혀 있어서, enum에 새 상수를 추가하면 제약이 옛 목록 그대로 남아
-- 새 값을 거부한다. 실제로 V20260728120000이 '서울_중구_전체'를 넣으려다 여기서
-- 막혀 dev 배포가 계속 실패했다.
--
-- 지금은 ddl-auto=validate라 Hibernate가 이 제약을 다시 만들지 않는다. 허용값
-- 검증은 애플리케이션의 Region3rd enum이 담당한다(prod가 이미 그렇게 동작 중).
--
-- 버전을 120000보다 낮게 둔 이유: dev는 아직 둘 다 적용 전이라 이게 먼저 실행돼야
-- 하고, 120000을 이미 적용한 prod에서는 out-of-order로 나중에 실행되며 IF EXISTS
-- 덕분에 아무 일도 하지 않는다. 적용이 끝난 마이그레이션을 고치면 체크섬이 깨지므로
-- 그쪽은 건드리지 않는다.

ALTER TABLE famous_menu_by_region
    DROP CONSTRAINT IF EXISTS famous_menu_by_region_region3rd_check;
