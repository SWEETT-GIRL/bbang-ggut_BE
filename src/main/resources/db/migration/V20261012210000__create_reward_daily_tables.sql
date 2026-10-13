-- 하루에 한 번 하는 것 둘 — 출석 7일판과 밥 룰렛.
--
-- 둘 다 "그날 했는가"를 PK 가 판정한다. 넣어 보고 DB 가 막으면 오늘은 이미 한 것이고, 그때는 오늘 결과를 그대로 돌려준다.
-- 날짜는 한국 날짜다(요청을 받은 서버 시각 기준, 앱이 보낸 값이 아니다).

-- PK 가 (날짜, 회원) 순서인 이유: 출석 리마인드 푸시가 "어제 출석한 회원 전체"를 날짜로 찾는다.
-- 회원 한 명의 어제·오늘 행을 찾는 조회도 이 PK 를 그대로 탄다.
CREATE TABLE member_attendance_days (
    attended_on DATE      NOT NULL,
    member_id   BIGINT    NOT NULL REFERENCES member (id),
    board_day   SMALLINT  NOT NULL,                             -- 7일판의 몇 번째 칸인가
    crumbs      INTEGER   NOT NULL,                             -- 실제로 받은 양. 1.x 이관으로 자리만 잡은 행과 제재 중 출석은 0
    created_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (attended_on, member_id),
    CONSTRAINT chk_member_attendance_board_day CHECK (board_day BETWEEN 1 AND 7)
);

CREATE TABLE member_feed_days (
    member_id  BIGINT    NOT NULL REFERENCES member (id),
    fed_on     DATE      NOT NULL,
    wedge      SMALLINT  NOT NULL,                              -- 룰렛이 멈춘 칸(0 부터, 시계 방향)
    crumbs     INTEGER   NOT NULL,                              -- 실제로 받은 양. 제재 중이면 0
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (member_id, fed_on)
);
