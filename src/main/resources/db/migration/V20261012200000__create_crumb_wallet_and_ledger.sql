-- 부스러기(리워드 2.0)의 정본. 지금까지는 앱이 기기에만 갖고 있어 계정을 옮기면 사라지고 시계를 돌리면 속았다.
--
-- 지갑은 잔액 한 줄, 원장은 그 잔액이 왜 그렇게 됐는지의 이력이다.
-- 모든 적립·차감은 지갑 행을 먼저 잠근 뒤 원장에 넣고 잔액을 고친다 — 회원 한 명의 변동이 한 줄로 선다.

-- 행은 지연 생성한다(첫 요약 조회나 첫 적립 때). 게스트에게는 만들지 않는다.
CREATE TABLE member_crumb_wallet (
    member_id       BIGINT    PRIMARY KEY REFERENCES member (id),
    balance         INTEGER   NOT NULL DEFAULT 0,
    carried_over_at TIMESTAMP,                                  -- 1.x 사용자 이관 선물을 준 때. 새 회원은 비어 있다
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    CONSTRAINT chk_member_crumb_wallet_balance CHECK (balance >= 0)
);

-- 같은 보상을 두 번 주지 않는 장치가 (member_id, idempotency_key) 유니크다.
-- 먼저 읽고 없으면 넣는 식이 아니라, 넣어 보고 DB 가 막았는지로 판정한다.
--
-- earned_on 은 한국 날짜다. 하루 상한·"오늘 받은 양"을 created_at 범위가 아니라 이 값의 등호로 센다 —
-- 요청 하나가 자정을 걸쳐도 날짜가 하나로 고정되고, 서버 시간대 설정에 기대지 않는다.
--
-- reason 에 CHECK 를 걸지 않는다 — 보상 종류는 phase 마다 늘어난다.
CREATE TABLE crumb_ledger (
    id              BIGSERIAL    PRIMARY KEY,
    member_id       BIGINT       NOT NULL REFERENCES member (id),
    amount          INTEGER      NOT NULL,                      -- 적립은 양수, 차감은 음수
    reason          VARCHAR(30)  NOT NULL,                      -- CrumbReason
    idempotency_key VARCHAR(100) NOT NULL,                      -- record:123, attendance:2026-10-12 …
    balance_after   INTEGER      NOT NULL,
    earned_on       DATE         NOT NULL,                      -- 한국 날짜
    ref_type        VARCHAR(20),                                -- 무엇 때문에 생겼는가(RECORD, STORE, COURSE …)
    ref_id          BIGINT,
    memo            VARCHAR(200),                               -- 어드민 지급·회수 사유
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_crumb_ledger_idempotency UNIQUE (member_id, idempotency_key),
    CONSTRAINT chk_crumb_ledger_amount CHECK (amount <> 0)
);

-- 받은 내역(영수증) — 최신순 커서. 같은 시각 행에서 건너뛰지 않게 id 로 넘긴다.
CREATE INDEX idx_crumb_ledger_member_recent ON crumb_ledger (member_id, id DESC);
-- 하루 상한·오늘 받은 양. 한 회원의 하루치는 수십 행이라 reason 은 읽어서 거른다.
CREATE INDEX idx_crumb_ledger_member_day ON crumb_ledger (member_id, earned_on);

-- 앱이 아직 연출하지 못한 보상. 초대 성사·랭킹처럼 요청 밖에서 생긴 것과, 연출을 못 보고 앱이 꺼진 레벨업을 담는다.
-- 앱이 연출을 끝내고 알리면(ack) 행을 지운다 — 지급 이력은 원장에 남는다.
CREATE TABLE member_pending_rewards (
    id         BIGSERIAL   PRIMARY KEY,
    member_id  BIGINT      NOT NULL REFERENCES member (id),
    dedupe_key VARCHAR(60) NOT NULL,                            -- 같은 연출이 두 번 쌓이지 않게(level_up:3 …)
    payload    JSONB       NOT NULL,                            -- 응답의 rewards[] 한 칸과 같은 모양
    created_at TIMESTAMP   NOT NULL,
    CONSTRAINT uq_member_pending_rewards UNIQUE (member_id, dedupe_key)
);
