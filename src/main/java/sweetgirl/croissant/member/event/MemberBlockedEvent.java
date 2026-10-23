package sweetgirl.croissant.member.event;

/** 새로 차단했을 때(이미 차단돼 있던 경우는 아님). 같은 트랜잭션 안에서 듣는다 — 팔로우가 끊기는 건 차단과 한 몸이다. */
public record MemberBlockedEvent(Long blockerId, Long blockedMemberId) {
}
