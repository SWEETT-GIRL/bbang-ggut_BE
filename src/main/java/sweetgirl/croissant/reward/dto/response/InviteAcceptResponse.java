package sweetgirl.croissant.reward.dto.response;

/** 초대 코드를 넣은 결과. 앱은 서로 팔로우된 초대한 사람의 프로필로 보낸다. */
public record InviteAcceptResponse(Long inviterMemberId, String inviterNickname) {
}
