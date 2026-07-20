package sweetgirl.croissant.member.dto.response;

public record GuestLoginResponse(String accessToken, String refreshToken, Long memberId, String nickname) {
}
