-- AdMob 보상형 광고의 서버 검증(SSV) 콜백을 받은 이력.
--
-- 두 가지를 한다.
--   1) 중복 적립 차단 — 구글은 콜백을 재시도한다. 같은 보상은 재시도돼도 transaction_id 가 같으므로,
--      이 값을 PK 로 두면 두 번째 삽입이 DB 에서 막힌다. 앱이 아니라 DB 가 막는 게 확실하다.
--   2) 정산 대조 — "이 사람이 광고 몇 편 봤나"를 나중에 광고 수익과 맞춰볼 수 있다.
--      member_characters.ad_views 는 깨어나면 더 안 오르고 해금 뒤 이력이 사라지므로 그 대조를 못 한다.
--
-- 행은 적립과 같은 트랜잭션에서 들어간다. 적립이 실패하면 이 행도 같이 사라져, 다음 재시도가 제대로 처리된다.
CREATE TABLE rewarded_ad_transaction (
    transaction_id VARCHAR(64)  PRIMARY KEY,                    -- 구글이 준 값. 재시도해도 같다
    member_id      BIGINT       NOT NULL REFERENCES member (id),
    character_code VARCHAR(30)  NOT NULL REFERENCES world_characters (code),
    ad_unit        VARCHAR(64),                                 -- 어느 광고 단위에서 났는가(정산 대조)
    reward_amount  INTEGER,
    reward_item    VARCHAR(64),
    created_at     TIMESTAMP    NOT NULL
);

-- "이 회원이 언제 몇 편 봤나" — CS 문의와 정산 대조가 보는 축.
CREATE INDEX idx_rewarded_ad_transaction_member
    ON rewarded_ad_transaction (member_id, created_at DESC);
