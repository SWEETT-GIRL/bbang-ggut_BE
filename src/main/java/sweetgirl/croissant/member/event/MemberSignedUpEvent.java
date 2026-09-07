package sweetgirl.croissant.member.event;

/**
 * 새 회원이 생겼다. 게스트 발급·소셜 최초 가입·탈퇴 후 재가입이 모두 여기 해당한다.
 *
 * <p>엔티티가 아니라 id만 실어 보낸다 — 리스너는 다른 트랜잭션·다른 스레드에서 돌아서
 * 여기 담긴 엔티티는 이미 준영속이다.
 */
public record MemberSignedUpEvent(Long memberId) {
}
