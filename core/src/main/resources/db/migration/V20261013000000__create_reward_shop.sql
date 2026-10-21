-- 부스러기를 쓰는 곳 — 상점(친구 모습 · 홈 소품), 부스러기로 친구 깨우기, 친구 이름.

-- 품목. 가격과 품목을 배포 없이 어드민에서 바꾼다.
--   POSE — 친구의 모습. 친구마다 따로 산다. pose 는 world_character_assets.pose 와 같은 값이다.
--   PROP — 홈 소품. 계정에 하나씩 가진다.
CREATE TABLE shop_items (
    code       VARCHAR(40) PRIMARY KEY,
    kind       VARCHAR(10) NOT NULL,
    pose       VARCHAR(20),
    name       VARCHAR(30) NOT NULL,
    price      INTEGER     NOT NULL,
    lock_level SMALLINT,                                        -- 이 레벨부터 살 수 있다. 없으면 누구나
    sort_order INTEGER     NOT NULL,
    is_new     BOOLEAN     NOT NULL DEFAULT FALSE,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,               -- 끄면 목록에서 빠진다. 이미 산 사람은 그대로 쓴다
    CONSTRAINT chk_shop_items_kind CHECK (kind IN ('POSE', 'PROP')),
    CONSTRAINT chk_shop_items_pose CHECK (kind <> 'POSE' OR pose IS NOT NULL),
    CONSTRAINT chk_shop_items_price CHECK (price > 0)
);

-- 품목이 바뀐 횟수. 앱은 이 값만 비교해 품목을 다시 받을지 정한다(world_catalog 와 같은 방식).
CREATE TABLE shop_catalog (
    id         SMALLINT  PRIMARY KEY DEFAULT 1,
    version    INTEGER   NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_shop_catalog_single_row CHECK (id = 1)
);
INSERT INTO shop_catalog (id, version, updated_at) VALUES (1, 1, CURRENT_TIMESTAMP);

-- 산 것. 모습은 (회원, 친구, 품목), 소품은 (회원, 품목)마다 하나다.
-- 소품은 친구가 없어 character_code 를 빈 문자열로 둔다 — NULL 로 두면 유니크가 같은 소품을 두 번 사는 것을 막지 못한다.
-- 그래서 character_code 에 외래키를 걸지 않는다(친구가 실제로 있는지는 구매할 때 확인한다).
CREATE TABLE member_shop_items (
    id             BIGSERIAL   PRIMARY KEY,
    member_id      BIGINT      NOT NULL REFERENCES member (id),
    character_code VARCHAR(30) NOT NULL DEFAULT '',
    item_code      VARCHAR(40) NOT NULL REFERENCES shop_items (code),
    purchased_at   TIMESTAMP   NOT NULL,
    CONSTRAINT uq_member_shop_items UNIQUE (member_id, character_code, item_code)
);

-- 친구가 지금 하고 있는 모습. 행이 없으면 기본 모습이다.
CREATE TABLE member_character_looks (
    member_id      BIGINT      NOT NULL REFERENCES member (id),
    character_code VARCHAR(30) NOT NULL REFERENCES world_characters (code),
    item_code      VARCHAR(40) NOT NULL REFERENCES shop_items (code),
    PRIMARY KEY (member_id, character_code)
);

-- 홈에 놓은 소품과, 친구에게 붙인 이름.
ALTER TABLE member_world ADD COLUMN home_prop_item VARCHAR(40) REFERENCES shop_items (code);
ALTER TABLE member_characters ADD COLUMN nickname VARCHAR(8);

INSERT INTO shop_items (code, kind, pose, name, price, lock_level, sort_order) VALUES
    ('pose-joy',         'POSE', 'joy',         '신났어',     90, NULL, 10),
    ('pose-music',       'POSE', 'music',       '흥얼흥얼',   90, NULL, 20),
    ('pose-wave',        'POSE', 'wave',        '손 흔들기', 120, NULL, 30),
    ('pose-explore',     'POSE', 'explore',     '탐험가',    150, NULL, 40),
    ('pose-camera',      'POSE', 'camera',      '사진사',    180, NULL, 50),
    ('pose-bread-tower', 'POSE', 'bread-tower', '발견했다',  180, 5,    60),
    ('prop-flag',        'PROP', NULL,          '빵 깃발',    50, NULL, 110),
    ('prop-milk',        'PROP', NULL,          '우유 컵',    50, NULL, 120),
    ('prop-cloud',       'PROP', NULL,          '구름 쿠션',  60, NULL, 130),
    ('prop-lights',      'PROP', NULL,          '별 전구',    80, NULL, 140);
