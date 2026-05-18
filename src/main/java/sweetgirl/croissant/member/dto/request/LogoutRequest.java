package sweetgirl.croissant.member.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LogoutRequest {

	// 클라이언트가 보낸 Refresh Token — DB에서 revoke 처리
	@NotBlank(message = "refreshToken은 필수입니다.")
	private String refreshToken;

	// 로그아웃하는 디바이스의 FCM 토큰 — 함께 비활성화하여 이후 푸시 미발송
	// optional: 푸시 권한 미허용 등으로 FCM 토큰이 없는 경우 null 허용
	@Size(max = 4096)
	private String fcmToken;
}
