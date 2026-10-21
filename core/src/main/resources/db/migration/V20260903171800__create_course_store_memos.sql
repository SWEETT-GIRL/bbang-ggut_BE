-- 코스 가게 메모를 한 칸(course_stores.memo)에서 (정류장 × 사람)으로 쪼갠다.
--
-- 키는 정류장 행(course_stores.id)이다. 메모는 "그 정류장"에 붙어서, 가게를 빼면 정류장 행과 함께
-- 메모도 전원 soft delete 되고 다시 담으면 새 정류장 행이 생기므로 돌아오지 않는다.
-- 방문 체크(course_store_visits)가 (course_id, store_id) 자연키로 "남기되 숨김"인 것과 일부러 다르다.
CREATE TABLE course_store_memos (
    course_store_id BIGINT       NOT NULL REFERENCES course_stores (id),
    member_id       BIGINT       NOT NULL REFERENCES member (id),
    content         VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP,              -- 가게 뺄 때·나가기·탈퇴
    PRIMARY KEY (course_store_id, member_id)
);

-- 방장이 쓴 기존 메모를 방장 메모로. CURATED 는 작성자가 없으니 그대로 둔다(코스 설명 역할, 어드민 경로가 계속 쓴다).
-- course_stores.memo 컬럼은 지우지 않는다.
INSERT INTO course_store_memos (course_store_id, member_id, content, created_at)
SELECT cs.id, c.member_id, LEFT(cs.memo, 200), NOW()
FROM course_stores cs
JOIN courses c ON c.id = cs.course_id
WHERE c.type IN ('CUSTOM', 'FORKED')
  AND c.member_id IS NOT NULL
  AND c.deleted_at IS NULL
  AND cs.deleted_at IS NULL
  AND cs.memo IS NOT NULL AND btrim(cs.memo) <> '';
