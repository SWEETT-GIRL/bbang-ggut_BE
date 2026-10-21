package sweetgirl.croissant.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMemberBanRequest;
import sweetgirl.croissant.admin.dto.request.AdminMemberRestrictionRequest;
import sweetgirl.croissant.admin.dto.request.AdminProfileResetRequest;
import sweetgirl.croissant.admin.dto.response.AdminMemberBanResponse;
import sweetgirl.croissant.admin.dto.response.AdminMemberRestrictionResponse;
import sweetgirl.croissant.admin.dto.response.AdminMemberSummaryResponse;
import sweetgirl.croissant.admin.dto.response.AdminProfileResetResponse;
import sweetgirl.croissant.admin.service.AdminMemberCommandService;
import sweetgirl.croissant.admin.service.AdminMemberQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Member", description = "어드민 회원 관리 API")
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

	private final AdminMemberCommandService adminMemberCommandService;
	private final AdminMemberQueryService adminMemberQueryService;

	@Operation(summary = "회원 검색", description = "닉네임 부분일치로 회원을 찾습니다. 검색어가 없으면 최근 가입순입니다. 편지 수신자 선택용.")
	@GetMapping
	public ResponseEntity<ApiResponse<AdminMemberSummaryResponse.ListResponse>> searchMembers(
			@Parameter(description = "닉네임 검색어") @RequestParam(required = false) String keyword,
			@Parameter(description = "0부터") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "기본 20, 최대 50") @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberQueryService.search(keyword, page, size)));
	}

	@Operation(summary = "회원 프로필 초기화", description = "회원 닉네임을 기본 정책값으로 초기화합니다.")
	@PostMapping("/{memberId}/profile-reset")
	public ResponseEntity<ApiResponse<AdminProfileResetResponse>> resetProfile(
			@Parameter(description = "회원 ID") @PathVariable Long memberId,
			@Valid @RequestBody AdminProfileResetRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberCommandService.resetProfile(memberId, request)));
	}

	@Operation(summary = "회원 제한 생성", description = "회원에게 경고, 작성 제한, 프로필 제한, 정지 제재를 부여합니다.")
	@PostMapping("/{memberId}/restrictions")
	public ResponseEntity<ApiResponse<AdminMemberRestrictionResponse>> createRestriction(
			@Parameter(description = "회원 ID") @PathVariable Long memberId,
			@Valid @RequestBody AdminMemberRestrictionRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberCommandService.createRestriction(memberId, request)));
	}

	@Operation(summary = "회원 강퇴", description = "회원을 영구 강퇴합니다. 강퇴된 회원은 동일 계정으로 재가입이 불가합니다.")
	@PostMapping("/{memberId}/ban")
	public ResponseEntity<ApiResponse<AdminMemberBanResponse>> banMember(
			@Parameter(description = "회원 ID") @PathVariable Long memberId,
			@Valid @RequestBody AdminMemberBanRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminMemberCommandService.banMember(memberId, request)));
	}
}
