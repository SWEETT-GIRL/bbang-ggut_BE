package sweetgirl.croissant.member.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.RefreshRequest;
import sweetgirl.croissant.member.dto.RefreshResponse;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	// Access Token 재발급
	// 클라이언트가 refreshToken을 바디로 보내면 새 accessToken을 발급해서 반환
	@PostMapping("/refresh")
	public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
		String newAccessToken = authService.refresh(request.getRefreshToken());
		return ApiResponse.ok(new RefreshResponse(newAccessToken));
	}

	// 로그아웃
	// refreshToken을 DB에서 삭제해서 해당 토큰으로 재발급 불가능하게 만듦
	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request.getRefreshToken());
		return ApiResponse.ok(null);
	}
}
