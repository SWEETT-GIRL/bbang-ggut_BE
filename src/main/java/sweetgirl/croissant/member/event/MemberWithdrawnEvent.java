package sweetgirl.croissant.member.event;

/**
 * 회원이 탈퇴했을 때. 같은 트랜잭션 안에서 듣는다 — 팔로우 · 좋아요 · 톡 · 멘션 · 같이 간 사람 행이 사람과 함께 사라져야 한다.
 * 빵로그 · 댓글은 지우지 않는다(안 보이게만, 작성자 가림). member 패키지가 각 도메인을 모르게 하려고 이벤트로 알린다.
 */
public record MemberWithdrawnEvent(Long memberId) {
}
