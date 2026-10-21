package sweetgirl.croissant.letter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.letter.dto.request.AdminLetterReplyRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterResolveRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterSendRequest;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendStatsResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendSummaryResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadDetailResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadListResponse;
import sweetgirl.croissant.letter.dto.response.LetterMessageResponse;
import sweetgirl.croissant.letter.service.AdminLetterService;

/**
 * 어드민 편지함.
 *
 * <p>이 경로는 {@code AdminApiKeyFilter}가 지킨다 — {@code /api/admin/**}이 SecurityConfig에서
 * permitAll이라, 편지 발송처럼 사용자에게 직접 닿는 API를 그대로 열어두면 아무나 개발자를
 * 사칭할 수 있다.
 */
@Tag(name = "Admin Letter", description = "어드민 편지함 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/letters")
public class AdminLetterController {

	private final AdminLetterService adminLetterService;

	@Operation(summary = "편지함 대화 목록", description = "사용자별 대화를 최근순으로 반환합니다. 마지막 편지와 안 읽은 수를 포함합니다.")
	@GetMapping("/threads")
	public ApiResponse<AdminLetterThreadListResponse> getThreads(
			@Parameter(description = "0부터") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "기본 20, 최대 50") @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(adminLetterService.getThreads(page, size));
	}

	@Operation(summary = "대화 내용 조회", description = "한 사용자와 주고받은 편지를 최신순으로 반환합니다.")
	@GetMapping("/threads/{threadId}")
	public ApiResponse<AdminLetterThreadDetailResponse> getThread(
			@Parameter(description = "대화방 ID") @PathVariable Long threadId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(adminLetterService.getThread(threadId, page, size));
	}

	@Operation(summary = "답장 보내기", description = "대화방에 답장을 보냅니다. 사용자가 보낸 편지는 함께 읽음 처리됩니다.")
	@PostMapping("/threads/{threadId}/messages")
	public ApiResponse<LetterMessageResponse> reply(
			@Parameter(description = "대화방 ID") @PathVariable Long threadId,
			@Valid @RequestBody AdminLetterReplyRequest request) {
		return ApiResponse.ok(adminLetterService.reply(threadId, request));
	}

	@Operation(summary = "새 편지 보내기", description = "지정한 회원들에게 같은 편지를 한 통씩 보냅니다. "
		+ "탈퇴·없는 회원은 건너뛰고 skippedMemberIds로 알려줍니다. 발송은 알림 한 건으로 남아 "
		+ "받은 사람 알림함에 쌓이고, push=true면 알림도 울립니다.")
	@PostMapping("/send")
	public ApiResponse<AdminLetterSendResponse> send(@Valid @RequestBody AdminLetterSendRequest request) {
		return ApiResponse.ok(adminLetterService.send(request));
	}

	@Operation(summary = "보낸 편지 목록", description = "여러 명에게 보낸 편지를 발송별로 최근순 반환합니다. "
		+ "받은 사람 수와 그 뒤로 답장한 사람 수를 포함합니다.")
	@GetMapping("/sends")
	public ApiResponse<AdminLetterSendSummaryResponse.Page> getSends(
			@Parameter(description = "0부터") @RequestParam(defaultValue = "0") int page,
			@Parameter(description = "기본 20, 최대 50") @RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(adminLetterService.getSends(page, size));
	}

	@Operation(summary = "보낸 편지 집계", description = "발송 한 번의 받은 사람·답장·보기 번호별 인원을 반환합니다. "
		+ "답장에 1~보기 개수의 숫자(①~ 포함)가 있으면 그 번호로 셉니다.")
	@GetMapping("/sends/{sendId}")
	public ApiResponse<AdminLetterSendStatsResponse> getSendStats(
			@Parameter(description = "발송 ID") @PathVariable Long sendId) {
		return ApiResponse.ok(adminLetterService.getSendStats(sendId));
	}

	@Operation(summary = "대화 해결 처리", description = "해결로 닫거나(resolved=true) 다시 엽니다(false). 닫으면 사용자가 다음에 "
		+ "편지함을 열 때 문의 유형을 다시 고릅니다. 사용자가 편지를 더 보내면 자동으로 열립니다.")
	@PatchMapping("/threads/{threadId}/resolve")
	public ApiResponse<Void> resolve(
			@Parameter(description = "대화방 ID") @PathVariable Long threadId,
			@Valid @RequestBody AdminLetterResolveRequest request) {
		adminLetterService.resolve(threadId, request);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "대화 읽음 처리", description = "사용자가 보낸 편지를 전부 읽음으로 만듭니다.")
	@PatchMapping("/threads/{threadId}/read")
	public ApiResponse<Void> markAsRead(@Parameter(description = "대화방 ID") @PathVariable Long threadId) {
		adminLetterService.markAsRead(threadId);
		return ApiResponse.ok(null);
	}
}
