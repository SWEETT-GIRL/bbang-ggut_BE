package sweetgirl.croissant.appversion.controller;

import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.appversion.dto.response.AppVersionPolicyResponse;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.appversion.service.AppVersionPolicyQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "AppVersion", description = "앱 버전 정책 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/app")
public class AppVersionPolicyController {

	private final AppVersionPolicyQueryService appVersionPolicyQueryService;

	@Operation(summary = "앱 버전 정책 조회", description = "앱이 시작할 때 자기 버전과 비교할 최소 지원 버전·최신 버전을 반환합니다. 로그인 없이 호출합니다.")
	@GetMapping("/version-policy")
	public ResponseEntity<ApiResponse<AppVersionPolicyResponse>> getPolicy(
			@Parameter(description = "IOS | ANDROID") @RequestParam AppPlatform platform) {
		// 앱 시작마다 부르는 요청 — 5분은 캐시해도 정책 반영이 늦는 정도가 체감되지 않는다.
		return ResponseEntity.ok()
			.cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
			.body(ApiResponse.ok(appVersionPolicyQueryService.getPolicy(platform)));
	}
}
