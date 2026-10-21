package sweetgirl.croissant.member.dto.response;

public record AppleLoginResponse(String accessToken, String refreshToken, boolean isNew) {
}
