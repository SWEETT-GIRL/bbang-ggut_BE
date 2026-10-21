package sweetgirl.croissant.appversion.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.appversion.dto.request.AppVersionPolicyUpdateRequest;
import sweetgirl.croissant.appversion.dto.response.AppVersionPolicyResponse;
import sweetgirl.croissant.appversion.enums.AppPlatform;
import sweetgirl.croissant.appversion.service.AdminAppVersionPolicyCommandService;
import sweetgirl.croissant.appversion.service.AppVersionPolicyQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin AppVersion", description = "어드민 앱 버전 정책 관리 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/app-version-policy")
public class AdminAppVersionPolicyController {

	private final AppVersionPolicyQueryService appVersionPolicyQueryService;
	private final AdminAppVersionPolicyCommandService adminAppVersionPolicyCommandService;

	@Operation(summary = "앱 버전 정책 조회")
	@GetMapping("/{platform}")
	public ResponseEntity<ApiResponse<AppVersionPolicyResponse>> getPolicy(
			@Parameter(description = "IOS | ANDROID") @PathVariable AppPlatform platform) {
		return ResponseEntity.ok(ApiResponse.ok(appVersionPolicyQueryService.getPolicy(platform)));
	}

	@Operation(summary = "앱 버전 정책 변경", description = "값을 통째로 교체합니다. minSupportedVersion에는 스토어에 이미 출시된 버전만 넣으세요 — "
		+ "없는 버전을 넣으면 사용자가 갈 곳이 없어 앱을 못 씁니다.")
	@PutMapping("/{platform}")
	public ResponseEntity<ApiResponse<AppVersionPolicyResponse>> updatePolicy(
			@Parameter(description = "IOS | ANDROID") @PathVariable AppPlatform platform,
			@RequestBody @Valid AppVersionPolicyUpdateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminAppVersionPolicyCommandService.upsert(platform, request)));
	}
}
