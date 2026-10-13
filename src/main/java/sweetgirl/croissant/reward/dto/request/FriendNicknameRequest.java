package sweetgirl.croissant.reward.dto.request;

/** @param nickname 친구에게 붙일 이름(1~8자). null 이거나 비어 있으면 기본 이름으로 되돌린다 */
public record FriendNicknameRequest(String nickname) {
}
