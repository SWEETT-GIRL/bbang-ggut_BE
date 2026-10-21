-- 선착순 쿠폰 발급의 동시성을 측정하기 위한 최소 스키마.
-- 설계: .claude/planning/feat-coupon-concurrency.md
--
-- 이 브랜치의 목적은 기능이 아니라 측정이다 — 비관적 락으로 발급을 만들고,
-- 재고 100장에 5만 건이 몰릴 때 무엇이 먼저 마르는지 본다.
-- 그래서 쿠폰 사용·만료·조회는 만들지 않는다. 필요해지면 그때 더한다.

CREATE TABLE coupons (
    id               BIGSERIAL     PRIMARY KEY,
    name             VARCHAR(100)  NOT NULL,
    total_quantity   INTEGER       NOT NULL,
    issued_quantity  INTEGER       NOT NULL DEFAULT 0,  -- 모든 요청이 줄 서는 단 하나의 지점
    opens_at         TIMESTAMP     NOT NULL,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP
);

-- issued_quantity <= total_quantity CHECK 를 일부러 걸지 않는다.
-- 락 없는 구현에서 초과 발급이 실제로 나는 것을 재현해야 하는데, 제약이 있으면 DB 가 먼저 막아
-- "락이 없으면 깨진다"를 보여줄 수 없다. 측정이 끝나고 이 컬럼을 계속 쓸지 정한 뒤에 추가한다.

-- 발급 한 건. 행이 곧 쿠폰 한 장이다.
-- coupon_id · member_id 를 연관관계가 아니라 식별자로 들고 있다 — 발급은 락을 쥔 채 일어나고,
-- 그 안에서 Member 를 로드하면 왕복이 하나 더 붙는다. 발급 경로에서 회원 정보를 읽을 일도 없다.
CREATE TABLE coupon_issues (
    id         BIGSERIAL  PRIMARY KEY,
    coupon_id  BIGINT     NOT NULL REFERENCES coupons (id),
    member_id  BIGINT     NOT NULL REFERENCES member (id),
    issued_at  TIMESTAMP  NOT NULL,
    -- 1인 1매의 최후 방어선. 락 방식을 바꿔도 이건 남는다.
    -- (coupon_id 선두 인덱스를 겸하므로 별도 인덱스를 두지 않는다)
    CONSTRAINT uq_coupon_issue UNIQUE (coupon_id, member_id)
);

-- created_at 을 따로 두지 않는다 — issued_at 이 곧 생성 시각이고,
-- 락을 쥔 채 INSERT 하므로 쓰는 컬럼을 최소로 둔다.
