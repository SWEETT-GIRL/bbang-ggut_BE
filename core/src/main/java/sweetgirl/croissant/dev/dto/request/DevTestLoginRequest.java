package sweetgirl.croissant.dev.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 닉네임으로 테스트 회원을 찾거나 만든다. deviceId 는 리프레시 토큰 행 구분용(없으면 닉네임). */
public record DevTestLoginRequest(
		@NotBlank @Size(max = 20) String nickname,
		String deviceId) {
}
