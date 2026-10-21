-- 빵투어(코스)를 같이 편집하는 사람들. 방장도 행을 갖는다(첫 초대 링크 생성 시 만든다).
--
-- role 컬럼이 없다 — 전원 편집자다. 초대 상태 컬럼도 없다 — 수락 = insert.
-- 방장 여부는 courses.member_id 로만 판정한다. 협업이 없는 코스엔 행이 없으므로 기존 코스 이관도 없다.
-- 나갔다 다시 들어오면 같은 PK 행의 deleted_at 을 비우고 joined_at·display_name 을 갱신한다(새 행 아님).
CREATE TABLE course_members (
    course_id     BIGINT      NOT NULL REFERENCES courses (id),
    member_id     BIGINT      NOT NULL REFERENCES member (id),
    display_name  VARCHAR(10) NOT NULL,   -- 코스 안에서 쓰는 이름. DB 길이 제약으로도 막는다
    joined_at     TIMESTAMP   NOT NULL,   -- 참여 순서 = 메모 정렬·승계 순서
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP,
    deleted_at    TIMESTAMP,              -- 나가기·탈퇴
    PRIMARY KEY (course_id, member_id)
);

-- "내가 참여한 코스" 목록용
CREATE INDEX idx_course_members_member ON course_members (member_id) WHERE deleted_at IS NULL;
