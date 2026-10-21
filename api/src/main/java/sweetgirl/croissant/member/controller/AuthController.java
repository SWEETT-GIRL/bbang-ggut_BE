package sweetgirl.croissant.member.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.request.AppleLoginRequest;
import sweetgirl.croissant.member.dto.request.GuestLoginRequest;
import sweetgirl.croissant.member.dto.request.KakaoLoginRequest;
import sweetgirl.croissant.member.dto.request.LogoutRequest;
import sweetgirl.croissant.member.dto.request.RefreshRequest;
import sweetgirl.croissant.member.dto.response.AppleLoginResponse;
import sweetgirl.croissant.member.dto.response.GuestLoginResponse;
import sweetgirl.croissant.member.dto.response.KakaoLoginResponse;
import sweetgirl.croissant.member.dto.response.RefreshResponse;
import sweetgirl.croissant.member.service.AppleAuthService;
import sweetgirl.croissant.member.service.AuthService;
import sweetgirl.croissant.member.service.GuestAuthService;
import sweetgirl.croissant.member.service.KakaoAuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final AppleAuthService appleAuthService;
	private final KakaoAuthService kakaoAuthService;
	private final GuestAuthService guestAuthService;

	@PostMapping("/guest")
	public ApiResponse<GuestLoginResponse> guestLogin(@RequestBody GuestLoginRequest request) {
		GuestLoginResponse response = guestAuthService.login(request.deviceId());
		return ApiResponse.ok(response);
	}

	@PostMapping("/kakao")
	public ApiResponse<KakaoLoginResponse> kakaoLogin(
			@RequestBody KakaoLoginRequest request,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		KakaoLoginResponse response = kakaoAuthService.login(request.accessToken(), request.deviceId(), authorization);
		return ApiResponse.ok(response);
	}

	@PostMapping("/apple")
	public ApiResponse<AppleLoginResponse> appleLogin(
			@RequestBody AppleLoginRequest request,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		AppleLoginResponse response = appleAuthService.login(request.identityToken(), request.deviceId(),
			authorization);
		return ApiResponse.ok(response);
	}

	// Access Token 재발급
	// 클라이언트가 refreshToken을 바디로 보내면 새 accessToken을 발급해서 반환
	@PostMapping("/refresh")
	public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
		return ApiResponse.ok(authService.refresh(request.getRefreshToken()));
	}

	// 로그아웃
	// refreshToken을 DB에서 revoke 처리, fcmToken이 함께 오면 비활성화도 같이 처리
	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.getRefreshToken(), request.getFcmToken());
		return ApiResponse.ok(null);
	}
}
