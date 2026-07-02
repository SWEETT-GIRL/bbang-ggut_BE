package sweetgirl.croissant.member.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.member.dto.request.BlockCreateRequest;
import sweetgirl.croissant.member.dto.response.BlockListResponse;
import sweetgirl.croissant.member.dto.response.BlockResponse;
import sweetgirl.croissant.member.service.MemberBlockCommandService;
import sweetgirl.croissant.member.service.MemberBlockQueryService;

@Tag(name = "Block", description = "사용자 차단 API")
@RestController
@RequestMapping("/blocks")
@RequiredArgsConstructor
public class MemberBlockController {

	private final MemberBlockQueryService memberBlockQueryService;
	private final MemberBlockCommandService memberBlockCommandService;

	@Operation(summary = "작성자 차단", description = "로그인 사용자가 특정 회원을 차단합니다.")
	@PostMapping
	public ResponseEntity<ApiResponse<BlockResponse>> blockMember(
			@AuthenticationPrincipal Long memberId,
			@RequestBody @Valid BlockCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(memberBlockCommandService.blockMember(memberId, request)));
	}

	@Operation(summary = "작성자 차단 해제", description = "로그인 사용자가 특정 회원 차단을 해제합니다.")
	@DeleteMapping("/{blockedMemberId}")
	public ResponseEntity<ApiResponse<Void>> unblockMember(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long blockedMemberId) {
		memberBlockCommandService.unblockMember(memberId, blockedMemberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "차단 목록 조회", description = "로그인 사용자의 차단 회원 목록을 최신순으로 조회합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<BlockListResponse>> getBlocks(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.ok(memberBlockQueryService.getBlocks(memberId, page, size)));
	}
}
