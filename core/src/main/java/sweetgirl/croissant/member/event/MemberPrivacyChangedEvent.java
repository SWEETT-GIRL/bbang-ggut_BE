package sweetgirl.croissant.member.event;

/**
 * 계정을 공개 ↔ 비공개로 바꿨을 때. 같은 트랜잭션 안에서 듣는다 — 공개로 돌리면 대기 중인 팔로우 요청이 함께 팔로워가 돼야 한다.
 * member 패키지가 follow 를 모르게 하려고 이벤트로 알린다.
 */
public record MemberPrivacyChangedEvent(Long memberId, boolean isPrivate) {
}
