-- 체인 지점. convenience_store 와 같은 꼴이고, 쓰임도 같다 — 브랜드 이름으로 가까운 지점을 찾는다.
--
-- 왜 store 를 안 쓰나: 빵긋 store 는 동네 빵집을 모은 표라 프랜차이즈가 거의 없다.
-- 파리바게뜨는 전국 3천 곳이 넘는데 store 에는 여덟 곳뿐이다(2026-09-22 확인).
-- 이름으로 찾는 길은 성립하지 않아 카카오에서 지점 목록을 따로 긁어 쌓는다.
--
-- 이 표가 생기면서 CHAIN 은 dessert_item_store 를 쓰지 않는다 →
-- dessert_item_store 는 V20260918200000 주석이 적은 대로 GENERIC 전용이 된다.
-- (운영 dessert_item_store 는 아직 비어 있어 옮길 행이 없다.)
--
-- brand 에 CHECK 를 걸지 않는다. 편의점은 넷으로 끝이지만 체인은 유행을 따라 계속 늘어난다 —
-- 체인 하나 붙일 때마다 마이그레이션을 쓰게 만들 이유가 없다.

CREATE TABLE chain_store (
    id              BIGSERIAL              PRIMARY KEY,
    brand           VARCHAR(50)            NOT NULL,
    branch_name     VARCHAR(100)           NOT NULL,
    address         VARCHAR(255)           NOT NULL,
    location        geography(Point, 4326) NOT NULL,
    kakao_place_id  VARCHAR(50)            NOT NULL UNIQUE,
    created_at      TIMESTAMP              NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP
);

CREATE INDEX idx_chain_store_location ON chain_store USING GIST (location);

-- 브랜드로 먼저 좁히고 반경을 잰다 — 파리바게뜨 한 곳만 3천 행이다.
CREATE INDEX idx_chain_store_brand ON chain_store (brand);

-- 파이프라인이 dataops_writer 롤로 쓴다. 롤이 없는 환경(로컬·CI·랩)에서는 조용히 건너뛴다.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_writer') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON chain_store TO dataops_writer;
        GRANT USAGE, SELECT ON SEQUENCE chain_store_id_seq TO dataops_writer;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'dataops_reader') THEN
        GRANT SELECT ON chain_store TO dataops_reader;
    END IF;
END $$;
