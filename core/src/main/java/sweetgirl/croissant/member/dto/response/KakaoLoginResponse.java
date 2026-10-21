package sweetgirl.croissant.member.dto.response;

public record KakaoLoginResponse(String accessToken, String refreshToken, boolean isNew) {
}
