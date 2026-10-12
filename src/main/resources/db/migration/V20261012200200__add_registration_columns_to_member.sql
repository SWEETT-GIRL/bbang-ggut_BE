-- 가입 시각과 재가입 표시.
--
-- registered_at — 소셜로 가입한 때. created_at 으로는 알 수 없다: 게스트로 쓰다 로그인하면 같은 행이
--   승격돼 created_at 이 게스트 발급 시각으로 남는다. 리워드의 "2.0 이전 가입자인가"가 이 값을 본다.
--   게스트는 비워 둔다.
-- previous_member_id — 탈퇴한 뒤 같은 소셜 계정으로 다시 가입한 회원이면, 탈퇴한 옛 회원 id.
--   환영 선물과 초대 코드 입력을 다시 받지 못하게 하는 데 쓴다.
ALTER TABLE member ADD COLUMN registered_at TIMESTAMP;
ALTER TABLE member ADD COLUMN previous_member_id BIGINT REFERENCES member (id);

-- 기존 회원 채우기. 승격한 회원은 소셜 연결이 생긴 때가, 재가입한 회원은 새 행이 생긴 때가 가입 시각이다
-- (재가입은 옛 연결 행을 새 회원으로 옮기므로 연결의 생성 시각이 더 이르다) — 둘 중 늦은 쪽이 맞다.
UPDATE member m
SET registered_at = GREATEST(m.created_at, linked.first_linked_at)
FROM (SELECT member_id, MIN(created_at) AS first_linked_at FROM oauth GROUP BY member_id) linked
WHERE linked.member_id = m.id;
