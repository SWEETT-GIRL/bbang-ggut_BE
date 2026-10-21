-- 빵별일지 세계관(빵탑·행성·캐릭터 해금)의 스키마. 실행 문서 「빵별일지-실행.md」 §3.
--
-- 설계 기조: 콘텐츠는 코드가 아니라 데이터다. 행성·고도 단계·캐릭터·해금 규칙·에셋 경로를 전부 표에 두고,
-- 어드민이 행을 더하는 것만으로 새 행성이 열린다. 서버 배포는 규칙의 *종류*가 바뀔 때만 한다.
-- 그래서 "지구는 3단계" "광고 2편" 같은 값이 코드 상수가 아니라 전부 컬럼으로 들어와 있다.

-- ─────────────────────────────────────────────────────────────────────────────
-- 콘텐츠 카탈로그 — 어드민이 만지는 표
-- ─────────────────────────────────────────────────────────────────────────────

-- 여정의 한 목적지. 태양계 8행성 → 외계(선택형) → 나만의 행성까지 같은 표로 표현한다.
--
-- span 이 절대 임계값이 아니라 "이 행성을 졸업하는 데 필요한 기록 수"인 이유:
--   외계 행성은 여럿 중 골라서 간다. 도착점을 절대 누적값으로 굳히면 소금빵 550 · 베이글 730 처럼 박혀
--   베이글을 먼저 고른 사람이 180 개를 더 걸어야 한다 — 선택형인데 고르는 순서가 난이도를 바꾼다.
--   절대 임계값은 서버가 unlock_after 사슬을 따라 누적해 만든다. 태양계는 결과가 같고(10/40/80/140/200) 분기만 성립한다.
CREATE TABLE world_planets (
    code          VARCHAR(30)  PRIMARY KEY,                             -- earth, mars, salt_bread, ...
    name          VARCHAR(30)  NOT NULL,
    planet_group  VARCHAR(20)  NOT NULL,                                -- SOLAR | ALIEN | CUSTOM
    sort_order    INTEGER      NOT NULL,                                -- 그룹 안의 순서
    span          INTEGER      NOT NULL,                                -- 이 행성 구간의 길이(졸업까지 필요한 기록 수)
    unlock_after  VARCHAR(30)  REFERENCES world_planets (code),         -- 이 행성을 지나야 열린다(직선 구간)
    selectable    BOOLEAN      NOT NULL DEFAULT FALSE,                  -- 같은 그룹에서 골라 가는가(외계)
    roulette_size INTEGER      NOT NULL DEFAULT 1,                      -- 도착 시 룰렛으로 주는 인원
    ads_per_wake  INTEGER      NOT NULL DEFAULT 2,                      -- 잠든 친구 하나당 광고 편수
    referral_ok   BOOLEAN      NOT NULL DEFAULT TRUE,                   -- 추천 지름길 허용
    image_key     VARCHAR(255),                                         -- 여정맵 아이콘
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP,
    CONSTRAINT uq_world_planet_order UNIQUE (planet_group, sort_order)
);

-- 행성 안의 고도 단계(마을·하늘·우주). 3 단계 고정이 아니라 행성마다 다를 수 있다.
-- offset_at 이 상대값인 이유도 span 과 같다 — 행성 순서가 바뀌어도 그대로 쓸 수 있어야 한다.
CREATE TABLE world_planet_stages (
    id          BIGSERIAL    PRIMARY KEY,
    planet_code VARCHAR(30)  NOT NULL REFERENCES world_planets (code) ON DELETE CASCADE,
    code        VARCHAR(20)  NOT NULL,                                  -- village | sky | space | ...
    name        VARCHAR(30)  NOT NULL,                                  -- 마을 / 하늘 / 우주
    stage_order INTEGER      NOT NULL,
    offset_at   INTEGER      NOT NULL,                                  -- 행성 도착점 기준 상대 위치(0 = 도착 즉시)
    image_key   VARCHAR(255),                                           -- 배경
    CONSTRAINT uq_world_stage UNIQUE (planet_code, stage_order)
);

-- 캐릭터(친구). 이름의 정본은 앱이 아니라 여기다 — 어드민에서 고치면 앱 배포 없이 바뀐다.
CREATE TABLE world_characters (
    code        VARCHAR(30) PRIMARY KEY,                                -- dog, cat, ... (앱 CharacterId 와 1:1)
    name        VARCHAR(30) NOT NULL,
    planet_code VARCHAR(30) NOT NULL REFERENCES world_planets (code),
    sort_order  INTEGER     NOT NULL,
    mascot      BOOLEAN     NOT NULL DEFAULT FALSE,                     -- 첫 설치 때 바로 주는 1 명
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uq_world_char_slot UNIQUE (planet_code, sort_order)
);
-- 마스코트는 앱 전체에 하나다. 둘이 되면 누구를 첫 친구로 줄지 서버가 못 정한다.
CREATE UNIQUE INDEX uq_world_character_mascot ON world_characters (mascot) WHERE mascot = TRUE;
CREATE INDEX idx_world_characters_planet ON world_characters (planet_code);

-- 포즈별 그림. 캐릭터마다 가진 포즈가 달라서 JSON 한 칸이 아니라 행으로 둔다.
-- 같은 그림을 교체할 땐 키에 버전을 넣는다(front_v2.png) — 같은 키를 덮으면 앱이 옛 그림을 계속 쓴다.
CREATE TABLE world_character_assets (
    character_code VARCHAR(30) NOT NULL REFERENCES world_characters (code) ON DELETE CASCADE,
    pose           VARCHAR(20) NOT NULL,                                -- front | sit | celebrate | silhouette | ...
    image_key      VARCHAR(255) NOT NULL,                               -- world/characters/dog/front_v1.png
    width          INTEGER,
    height         INTEGER,
    PRIMARY KEY (character_code, pose)
);

-- 카탈로그가 바뀐 횟수. 앱은 이 값만 비교해 카탈로그를 다시 받을지 정하고, 서버도 이 값으로 캐시를 푼다.
-- 어드민이 행성·단계·캐릭터·에셋 중 무엇이든 저장하면 같은 트랜잭션에서 +1 한다.
CREATE TABLE world_catalog (
    id         SMALLINT  PRIMARY KEY DEFAULT 1,
    version    INTEGER   NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_world_catalog_single_row CHECK (id = 1)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 회원 상태 — 행은 지연 생성한다(광고를 처음 보거나 해금될 때만 만든다)
-- ─────────────────────────────────────────────────────────────────────────────

-- max_record_count 는 "최고로 높이 쌓았던 높이"다. 기록을 지우면 빵탑에서는 빠지지만 행성은 안 빼앗는다.
CREATE TABLE member_world (
    member_id          BIGINT      PRIMARY KEY REFERENCES member (id),
    selected_character VARCHAR(30) NOT NULL REFERENCES world_characters (code),
    max_record_count   INTEGER     NOT NULL DEFAULT 0,
    welcomed_at        TIMESTAMP,                                       -- 첫 친구 연출을 봤는가
    created_at         TIMESTAMP   NOT NULL,
    updated_at         TIMESTAMP
);

-- 잠든 친구는 행이 없다가 광고를 한 편이라도 보면 SLEEPING 으로 생기고, 다 보면 AWAKE 가 된다.
CREATE TABLE member_characters (
    member_id      BIGINT      NOT NULL REFERENCES member (id),
    character_code VARCHAR(30) NOT NULL REFERENCES world_characters (code),
    status         VARCHAR(10) NOT NULL,                                -- SLEEPING | AWAKE
    ad_views       INTEGER     NOT NULL DEFAULT 0,
    acquired_by    VARCHAR(12),                                         -- FIRST | ROULETTE | AD | REFERRAL | ADMIN
    acquired_at    TIMESTAMP,
    PRIMARY KEY (member_id, character_code),
    CONSTRAINT chk_member_character_status CHECK (status IN ('SLEEPING', 'AWAKE')),
    CONSTRAINT chk_member_character_acquired CHECK (
        (status = 'AWAKE'    AND acquired_by IS NOT NULL AND acquired_at IS NOT NULL)
     OR (status = 'SLEEPING' AND acquired_by IS NULL     AND acquired_at IS NULL))
);
CREATE INDEX idx_member_characters_member ON member_characters (member_id);

-- 도착 이력. 진행도 판정이 이 표를 함께 보는 이유: 운영 중 임계값을 올려도 이미 도착한 유저를 뒤로 밀지 않는다.
CREATE TABLE member_planet_arrivals (
    member_id   BIGINT      NOT NULL REFERENCES member (id),
    planet_code VARCHAR(30) NOT NULL REFERENCES world_planets (code),
    arrived_at  TIMESTAMP   NOT NULL,
    roulette_at TIMESTAMP,                                              -- 돌렸으면 채워진다
    PRIMARY KEY (member_id, planet_code)
);

-- 추천 지름길: 친구가 내 코드로 가입하면 광고를 건너뛸 수 있는 티켓이 한 장 쌓인다.
CREATE TABLE member_wake_tickets (
    id         BIGSERIAL   PRIMARY KEY,
    member_id  BIGINT      NOT NULL REFERENCES member (id),
    source     VARCHAR(12) NOT NULL,                                    -- REFERRAL | ADMIN | EVENT
    ref_member BIGINT      REFERENCES member (id),                      -- 나를 통해 가입한 사람(중복 지급 방지)
    used_at    TIMESTAMP,
    created_at TIMESTAMP   NOT NULL
);
-- 같은 사람이 가입했다고 두 장이 나오면 안 된다.
CREATE UNIQUE INDEX uq_wake_ticket_referral ON member_wake_tickets (ref_member) WHERE source = 'REFERRAL';
CREATE INDEX idx_wake_tickets_unused ON member_wake_tickets (member_id) WHERE used_at IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 기존 표에 붙는 칸
-- ─────────────────────────────────────────────────────────────────────────────

-- 빵탑에 쌓은 빵. 기록을 지우면 같이 사라진다. 빵 종류가 늘 수 있어 CHECK 는 걸지 않는다.
-- 컬럼만 먼저 만든다 — 엔티티 매핑과 저장 로직은 기록 연동 작업에서 붙인다.
ALTER TABLE bread_record ADD COLUMN tower_bread VARCHAR(20);

-- ─────────────────────────────────────────────────────────────────────────────
-- seed — 1 차 출시분(5 행성 · 15 단계 · 20 캐릭터)
-- ─────────────────────────────────────────────────────────────────────────────

-- span 을 누적하면 도착점이 10 / 40 / 80 / 140 / 200 이 된다(확정 임계값).
-- 지구는 roulette_size 가 0 이다 — 첫 친구는 룰렛이 아니라 마스코트로 바로 준다.
INSERT INTO world_planets (code, name, planet_group, sort_order, span, unlock_after,
                           roulette_size, ads_per_wake, active, created_at) VALUES
 ('earth',   '지구',   'SOLAR', 1, 10, NULL,      0, 2, TRUE, CURRENT_TIMESTAMP),
 ('mars',    '화성',   'SOLAR', 2, 30, 'earth',   1, 2, TRUE, CURRENT_TIMESTAMP),
 ('jupiter', '목성',   'SOLAR', 3, 40, 'mars',    1, 2, TRUE, CURRENT_TIMESTAMP),
 ('saturn',  '토성',   'SOLAR', 4, 60, 'jupiter', 1, 2, TRUE, CURRENT_TIMESTAMP),
 ('neptune', '해왕성', 'SOLAR', 5, 60, 'saturn',  1, 2, TRUE, CURRENT_TIMESTAMP);

-- 고도는 구간을 3 등분한 상대값이다. 지구만 구간이 짧아 0 · 3 · 7 로 촘촘하다.
INSERT INTO world_planet_stages (planet_code, code, name, stage_order, offset_at) VALUES
 ('earth',   'village', '마을', 1, 0), ('earth',   'sky', '하늘', 2,  3), ('earth',   'space', '우주', 3,  7),
 ('mars',    'village', '마을', 1, 0), ('mars',    'sky', '하늘', 2, 10), ('mars',    'space', '우주', 3, 20),
 ('jupiter', 'village', '마을', 1, 0), ('jupiter', 'sky', '하늘', 2, 13), ('jupiter', 'space', '우주', 3, 27),
 ('saturn',  'village', '마을', 1, 0), ('saturn',  'sky', '하늘', 2, 20), ('saturn',  'space', '우주', 3, 40),
 ('neptune', 'village', '마을', 1, 0), ('neptune', 'sky', '하늘', 2, 20), ('neptune', 'space', '우주', 3, 40);

-- 캐릭터 20 종, 행성당 4 종. 이름 11 종(햄스터·코알라·수달·쿼카·라쿤·물범·소·나무늘보·공룡·알파카·레이서판다)은
-- 아직 종명 그대로다 — 이름 정본이 DB 에 있어 출시 전 어드민에서 앱 배포 없이 갈아끼운다.
INSERT INTO world_characters (code, name, planet_code, sort_order, mascot, active, created_at) VALUES
 ('dog',         '빵댕이',      'earth',   1, TRUE,  TRUE, CURRENT_TIMESTAMP),
 ('cat',         '냥이',        'earth',   2, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('rabbit',      '토롱이',      'earth',   3, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('penguin',     '펭이',        'earth',   4, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('chick',       '삐약이',      'mars',    1, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('hamster',     '햄스터',      'mars',    2, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('frog',        '개굴이',      'mars',    3, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('duck',        '파덕이',      'mars',    4, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('ber',         '곰도리',      'jupiter', 1, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('coala',       '코알라',      'jupiter', 2, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('otter',       '수달',        'jupiter', 3, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('quoka',       '쿼카',        'jupiter', 4, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('racoon',      '라쿤',        'saturn',  1, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('seal',        '물범',        'saturn',  2, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('cow',         '소',          'saturn',  3, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('sloth',       '나무늘보',    'saturn',  4, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('dino',        '공룡',        'neptune', 1, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('alpaca',      '알파카',      'neptune', 2, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('racerpanda',  '레이서판다',  'neptune', 3, FALSE, TRUE, CURRENT_TIMESTAMP),
 ('oompaloompa', '움파룸파',    'neptune', 4, FALSE, TRUE, CURRENT_TIMESTAMP);

-- 캐릭터 그림(world_character_assets)은 여기서 넣지 않는다. S3 에 올린 뒤 어드민으로 채운다 —
-- 그때까지 앱은 번들 이미지를 쓰고, 서버는 키가 없는 포즈를 응답에서 뺀다.

INSERT INTO world_catalog (id, version, updated_at) VALUES (1, 1, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
