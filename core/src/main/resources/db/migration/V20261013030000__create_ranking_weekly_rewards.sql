-- 빵순이 랭킹 주간 보상(리워드 2.0). 월요일마다 지난주(월~일) 전국 순위 1~10 위에게 한 줄씩 생긴다.
--
-- 자동으로 주지 않고 회원이 "받기"를 눌러 받는다(claimed_at). 받을 수 있게 된 월요일부터 4 주가 지나면 못 받는다 —
-- 기한은 week_start + 35 일로 늘 같아서 칸으로 두지 않고 계산한다.
-- crown_until 은 1 위만 채운다. 그날까지 왕관을 쓴다(받기를 누르지 않아도 쓴다).
-- 순위 칸의 이름이 rank 가 아닌 이유: rank 는 SQL 의 창 함수 이름이라 테스트 DB 에서 예약어로 걸린다.
CREATE TABLE ranking_weekly_rewards (
    id          BIGSERIAL PRIMARY KEY,
    week_start  DATE      NOT NULL,                             -- 순위를 겨룬 주의 월요일
    member_id   BIGINT    NOT NULL REFERENCES member (id),
    final_rank  SMALLINT  NOT NULL,
    crumbs      INTEGER   NOT NULL,
    crown_until DATE,
    claimed_at  TIMESTAMP,
    created_at  TIMESTAMP NOT NULL,
    CONSTRAINT uq_ranking_weekly_rewards UNIQUE (week_start, member_id),
    CONSTRAINT chk_ranking_weekly_rewards_rank CHECK (final_rank BETWEEN 1 AND 10)
);

-- 홈 요약이 매번 "아직 안 받은 것"을 찾는다.
CREATE INDEX idx_ranking_weekly_rewards_unclaimed ON ranking_weekly_rewards (member_id) WHERE claimed_at IS NULL;
