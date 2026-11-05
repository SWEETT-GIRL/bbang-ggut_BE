-- 팔로우 소급 (2026-10-21). 앞으로는 초대 코드 수락 · 빵투어 일행 합류 때 서로 자동 팔로우하므로,
-- 이미 맺어진 쌍도 같은 상태로 맞춘다. 일반 회원(USER)끼리 · 탈퇴 아님 · 차단 관계 아님만. 이미 있으면 건너뛴다.

-- 1) 초대 코드: 초대한 사람 ↔ 초대받은 사람
INSERT INTO member_follow (follower_id, followee_id, created_at, updated_at)
SELECT pair.follower_id, pair.followee_id, now(), now()
FROM (
    SELECT i.inviter_id AS follower_id, i.invitee_id AS followee_id FROM member_invites i
    UNION
    SELECT i.invitee_id, i.inviter_id FROM member_invites i
) pair
JOIN member follower ON follower.id = pair.follower_id
    AND follower.deleted_at IS NULL AND follower.member_type = 'USER'
JOIN member followee ON followee.id = pair.followee_id
    AND followee.deleted_at IS NULL AND followee.member_type = 'USER'
WHERE NOT EXISTS (
    SELECT 1 FROM member_block b
    WHERE (b.blocker_id = pair.follower_id AND b.blocked_member_id = pair.followee_id)
       OR (b.blocker_id = pair.followee_id AND b.blocked_member_id = pair.follower_id))
ON CONFLICT DO NOTHING;

-- 2) 빵투어 일행: 같은 코스의 활성 멤버끼리 전원
INSERT INTO member_follow (follower_id, followee_id, created_at, updated_at)
SELECT DISTINCT x.member_id, y.member_id, now(), now()
FROM course_members x
JOIN course_members y ON y.course_id = x.course_id AND y.member_id <> x.member_id
JOIN member follower ON follower.id = x.member_id
    AND follower.deleted_at IS NULL AND follower.member_type = 'USER'
JOIN member followee ON followee.id = y.member_id
    AND followee.deleted_at IS NULL AND followee.member_type = 'USER'
WHERE x.deleted_at IS NULL AND y.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM member_block b
    WHERE (b.blocker_id = x.member_id AND b.blocked_member_id = y.member_id)
       OR (b.blocker_id = y.member_id AND b.blocked_member_id = x.member_id))
ON CONFLICT DO NOTHING;
