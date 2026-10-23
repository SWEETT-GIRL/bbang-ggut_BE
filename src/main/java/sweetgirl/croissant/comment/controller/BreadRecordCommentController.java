package sweetgirl.croissant.comment.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.comment.dto.request.CommentCreateRequest;
import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.comment.dto.response.CommunityAgreementResponse;
import sweetgirl.croissant.comment.dto.response.MentionCandidateResponse;
import sweetgirl.croissant.comment.service.CommentCommandService;
import sweetgirl.croissant.comment.service.CommentQueryService;
import sweetgirl.croissant.comment.service.CommunityAgreementService;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;

@Tag(name = "[Client] 빵로그 댓글", description = "댓글 · 멘션 · 커뮤니티 규칙 동의")
@RestController
@RequiredArgsConstructor
public class BreadRecordCommentController {

	private final CommentQueryService commentQueryService;
	private final CommentCommandService commentCommandService;
	private final CommunityAgreementService communityAgreementService;

	@Operation(summary = "댓글 목록", description = "오래된 순, offset. 지운 댓글 · 차단 관계 · 숨긴 댓글은 빠진다. "
		+ "줄마다 isAuthor(빵로그 쓴 사람) · isMine · 멘션 위치. 기록을 볼 수 없으면 403 BREAD_RECORD403_NOT_VISIBLE.")
	@GetMapping("/bread-records/{recordId}/comments")
	public ApiResponse<SliceResponse<CommentResponse>> getComments(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long recordId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(commentQueryService.getComments(viewerId, recordId, page, size));
	}

	@Operation(summary = "댓글 쓰기", description = "공개 빵로그만. 200자 · 줄바꿈 3줄 · 대댓글 없음 · 수정 없음. "
		+ "400 FORBIDDEN_WORD · LINK · PHONE · COMMENT400_TOO_LONG, 429 TOO_FAST(30초 3개), "
		+ "403 COMMUNITY_AGREEMENT_REQUIRED · MEMBER403_GUEST · MEMBER403_WRITE_RESTRICTED. "
		+ "멘션은 mentions: [{memberId, start, length}] 최대 3명(차단 · 탈퇴한 사람이면 400 COMMENT400_MENTION_INVALID).")
	@PostMapping("/bread-records/{recordId}/comments")
	public ResponseEntity<ApiResponse<CommentResponse>> create(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long recordId,
			@RequestBody @Valid CommentCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(commentCommandService.create(memberId, recordId, request)));
	}

	@Operation(summary = "댓글 지우기", description = "내 댓글, 또는 내 빵로그의 남의 댓글. 아니면 403 COMMENT403_DELETE.")
	@DeleteMapping("/comments/{commentId}")
	public ApiResponse<Void> delete(@AuthenticationPrincipal Long memberId, @PathVariable Long commentId) {
		commentCommandService.delete(memberId, commentId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "멘션 후보", description = "팔로잉 · 팔로워 · 이 빵로그에 댓글 단 사람 중 앞글자 일치, 20명. "
		+ "relation = FOLLOWING | FOLLOWER | COMMENTER. 게스트 403 MEMBER403_GUEST.")
	@GetMapping("/bread-records/{recordId}/mention-candidates")
	public ApiResponse<List<MentionCandidateResponse>> mentionCandidates(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long recordId,
			@RequestParam(name = "q", defaultValue = "") String query) {
		return ApiResponse.ok(commentQueryService.mentionCandidates(viewerId, recordId, query));
	}

	@Operation(summary = "커뮤니티 규칙 동의 여부", description = "agreed 가 false 면 첫 댓글 · 첫 공개 전환 전에 규칙 시트를 띄운다(08-D1 · D2). "
		+ "규칙 버전이 오르면 다시 false.")
	@GetMapping("/members/me/community-agreement")
	public ApiResponse<CommunityAgreementResponse> getAgreement(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(communityAgreementService.get(memberId));
	}

	@Operation(summary = "커뮤니티 규칙 동의", description = "지금 버전에 동의한 것으로 저장한다. 게스트 403 MEMBER403_GUEST.")
	@PostMapping("/members/me/community-agreement")
	public ApiResponse<CommunityAgreementResponse> agree(@AuthenticationPrincipal Long memberId) {
		return ApiResponse.ok(communityAgreementService.agree(memberId));
	}
}
