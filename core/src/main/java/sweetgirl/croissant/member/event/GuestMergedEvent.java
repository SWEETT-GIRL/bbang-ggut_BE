package sweetgirl.croissant.member.event;

/**
 * 게스트 데이터를 회원 계정으로 옮기고 커밋한 뒤 발행한다. 취향 프로필(personalization)이 회원을 다시 계산하는 데 쓴다 — 병합
 * 트랜잭션 안에서 계산하면 가게 프로필 데이터 문제가 로그인 병합 자체를 되돌릴 수 있어 커밋 뒤 따로 돈다.
 */
public record GuestMergedEvent(Long guestId, Long targetId) {
}
