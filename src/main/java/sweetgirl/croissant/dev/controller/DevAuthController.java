package sweetgirl.croissant.dev.controller;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.request.DevTestLoginRequest;
import sweetgirl.croissant.dev.dto.response.DevTestLoginResponse;
import sweetgirl.croissant.dev.service.DevAuthService;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 테스트 회원 로그인. <b>local·staging 에서만 뜬다 — prod 에는 올라가지 않는다.</b>
 *
 * <p>local 밖에서는 X-Dev-Test-Key 헤더를 요구한다. 키는 cd-staging 이 저장소 변수에서 내려준다 — 비어 있으면 그 환경에서는 닫힌다.
 */
@Profile({"local", "staging"})
@Tag(name = "[Dev] 테스트 로그인", description = "카카오 없이 닉네임으로 테스트 회원 토큰을 받는다 (local·staging 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/dev/auth")
public class DevAuthController {

	private final DevAuthService devAuthService;
	private final Environment environment;

	@Value("${dev.test-login.key:}")
	private String testLoginKey;

	@Operation(summary = "테스트 회원 로그인", description = "닉네임의 USER 회원을 찾거나 만들고 평소와 같은 accessToken · refreshToken 을 준다. "
		+ "local 밖에서는 X-Dev-Test-Key 헤더가 필요하다.")
	@PostMapping("/test-login")
	public ApiResponse<DevTestLoginResponse> testLogin(
			@RequestHeader(value = "X-Dev-Test-Key", required = false) String accessKey,
			@Valid @RequestBody DevTestLoginRequest request) {
		validateAccess(accessKey);
		return ApiResponse.ok(devAuthService.login(request.nickname().strip(), request.deviceId()));
	}

	/** {@code DevCouponController.validateAccess} 와 같은 규칙 — local 은 통과, 그 밖은 키를 본다. */
	private void validateAccess(String accessKey) {
		if (Arrays.asList(environment.getActiveProfiles()).contains("local")) {
			return;
		}
		if (!StringUtils.hasText(testLoginKey) || !testLoginKey.equals(accessKey)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "dev test login denied");
		}
	}
}
