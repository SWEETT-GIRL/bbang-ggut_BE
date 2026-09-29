-- 빵로그 랭킹 "내 지역". 앱이 지역을 보내게 하면 여러 지역 판을 돌아가며 볼 수 있어 서버가 들고 있는다.
-- 바꾸기는 30일에 한 번이다(처음 정한 뒤 24시간 안의 한 번은 잘못 누른 것으로 보고 봐준다) —
-- 판정에 마지막으로 바꾼 시각과 바꾼 횟수를 쓴다.
CREATE TABLE member_ranking_region (
    member_id    BIGINT      PRIMARY KEY REFERENCES member (id),
    sido         VARCHAR(10) NOT NULL,                                   -- 짧은 시·도(서울·경기…) — bread_record.region_key 첫 칸과 같은 표기
    changed_at   TIMESTAMP   NOT NULL,                                   -- 마지막으로 정하거나 바꾼 시각(Asia/Seoul)
    change_count INTEGER     NOT NULL DEFAULT 1,                         -- 처음 정한 것도 1 번으로 센다
    created_at   TIMESTAMP   NOT NULL,
    updated_at   TIMESTAMP
);
