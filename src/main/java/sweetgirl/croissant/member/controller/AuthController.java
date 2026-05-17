package sweetgirl.croissant.member.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.request.AppleLoginRequest;
import sweetgirl.croissant.member.dto.request.LogoutRequest;
import sweetgirl.croissant.member.dto.request.RefreshRequest;
import sweetgirl.croissant.member.dto.response.AppleLoginResponse;
import sweetgirl.croissant.member.dto.response.RefreshResponse;
import sweetgirl.croissant.member.service.AppleAuthService;
import sweetgirl.croissant.member.service.AuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	private final AppleAuthService appleAuthService;

	@PostMapping("/apple")
	public ResponseEntity<?> appleLogin(@RequestBody AppleLoginRequest req) {
		AppleLoginResponse response = appleAuthService.login(req.identityToken(), req.deviceId());
		return ResponseEntity.ok(Map.of("data", response));
	}

	// Access Token 재발급
	// 클라이언트가 refreshToken을 바디로 보내면 새 accessToken을 발급해서 반환
	@PostMapping("/refresh")
	public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
		String newAccessToken = authService.refresh(request.getRefreshToken());
		return ApiResponse.ok(new RefreshResponse(newAccessToken));
	}

	// 로그아웃
	// refreshToken을 DB에서 revoke 처리, fcmToken이 함께 오면 비활성화도 같이 처리
	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.getRefreshToken(), request.getFcmToken());
		return ApiResponse.ok(null);
	}
}
