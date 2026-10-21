-- 미션 완료를 기록으로 남긴다.
--
-- 미션은 지금까지 조회할 때마다 현재 상태로 다시 계산했다. 그래서 끝낸 미션이라도
-- 근거가 사라지면(기록을 지우면) 다시 '미완료'로 돌아갔다 — 뱃지는 member_badges에
-- 남아 유지되는데 미션만 풀려서, 홈 체크리스트가 되살아났다.
-- 한 번 해낸 일은 되돌리지 않는다. 뱃지와 같은 모양으로 완료 시점을 남긴다.
CREATE TABLE member_missions (
    member_id     BIGINT     NOT NULL REFERENCES member (id),
    mission_id    BIGINT     NOT NULL REFERENCES missions (id),
    completed_at  TIMESTAMP  NOT NULL,
    PRIMARY KEY (member_id, mission_id)
);

CREATE INDEX idx_member_missions_member_completed
    ON member_missions (member_id, completed_at DESC);
