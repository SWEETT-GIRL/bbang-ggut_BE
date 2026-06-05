package sweetgirl.croissant.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMemberRestrictionRequest;
import sweetgirl.croissant.admin.dto.request.AdminProfileResetRequest;
import sweetgirl.croissant.admin.dto.response.AdminMemberRestrictionResponse;
import sweetgirl.croissant.admin.dto.response.AdminProfileResetResponse;
import sweetgirl.croissant.admin.service.AdminMemberService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Member", description = "어드민 회원 관리 API")
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

	private final AdminMemberService adminMemberService;

	@Operation(summary = "회원 프로필 초기화", description = "회원 닉네임을 기본 정책값으로 초기화합니다.")
	@PostMapping("/{memberId}/profile-reset")
	public ResponseEntity<ApiResponse<AdminProfileResetResponse>> resetProfile(
			@Parameter(description = "회원 ID") @PathVariable Long memberId,
			@Valid @RequestBody AdminProfileResetRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberService.resetProfile(memberId, request)));
	}

	@Operation(summary = "회원 제한 생성", description = "회원에게 경고, 작성 제한, 프로필 제한, 정지 제재를 부여합니다.")
	@PostMapping("/{memberId}/restrictions")
	public ResponseEntity<ApiResponse<AdminMemberRestrictionResponse>> createRestriction(
			@Parameter(description = "회원 ID") @PathVariable Long memberId,
			@Valid @RequestBody AdminMemberRestrictionRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberService.createRestriction(memberId, request)));
	}
}
