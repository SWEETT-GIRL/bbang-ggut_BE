package sweetgirl.croissant.breadlog.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordDetailResponse;
import sweetgirl.croissant.breadlog.dto.response.MemberBreadRecordResponse;
import sweetgirl.croissant.breadlog.enums.BreadRecordSort;
import sweetgirl.croissant.breadlog.service.BreadRecordCompanionService;
import sweetgirl.croissant.breadlog.service.BreadRecordLikeCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordSocialQueryService;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;

/** 남에게 보이는 빵로그 — 상세 · 좋아요 · 프로필 목록. 내 타임라인 · 쓰기는 {@link BreadRecordController}. */
@Tag(name = "[Client] 빵로그 소셜", description = "빵로그 상세 · 좋아요 · 프로필 공개 빵로그")
@RestController
@RequiredArgsConstructor
public class BreadRecordSocialController {

	private final BreadRecordSocialQueryService breadRecordSocialQueryService;
	private final BreadRecordLikeCommandService breadRecordLikeCommandService;
	private final BreadRecordCompanionService companionService;

	@Operation(summary = "빵로그 상세", description = "남의 것은 공개 · 숨김 아님 · 차단 아님일 때만. "
		+ "아니면 403 BREAD_RECORD403_NOT_VISIBLE. 없으면 404. 내 것은 비공개여도 보이고 신고로 숨겨졌으면 hiddenStatus UNDER_REVIEW.")
	@GetMapping("/bread-records/{recordId}")
	public ApiResponse<BreadRecordDetailResponse> getDetail(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long recordId) {
		return ApiResponse.ok(breadRecordSocialQueryService.getDetail(viewerId, recordId));
	}

	@Operation(summary = "같이 간 사람 태그에서 나 빼기", description = "태그된 사람이 스스로 뺀다 — 알림 없음, 작성자 화면에서도 빠지고 "
		+ "같은 빵로그에 다시 태그되지 않는다. 태그된 적 없으면 404 COMPANION404_NOT_TAGGED, 이미 뺐으면 그대로 200.")
	@DeleteMapping("/bread-records/{recordId}/companions/me")
	public ApiResponse<Void> removeMeFromCompanions(@AuthenticationPrincipal Long viewerId,
			@PathVariable Long recordId) {
		companionService.removeBySelf(recordId, viewerId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "좋아요", description = "공개 빵로그만, 멱등 200, 부스러기 없음. "
		+ "게스트 403 MEMBER403_GUEST · 정지 403 · 볼 수 없는 기록 403.")
	@PutMapping("/bread-records/{recordId}/like")
	public ApiResponse<Void> like(@AuthenticationPrincipal Long viewerId, @PathVariable Long recordId) {
		breadRecordLikeCommandService.like(viewerId, recordId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "좋아요 취소", description = "안 눌렀어도 200.")
	@DeleteMapping("/bread-records/{recordId}/like")
	public ApiResponse<Void> unlike(@AuthenticationPrincipal Long viewerId, @PathVariable Long recordId) {
		breadRecordLikeCommandService.unlike(viewerId, recordId);
		return ApiResponse.ok(null);
	}

	@Operation(summary = "좋아요 누른 사람", description = "최신순, 줄마다 followedByMe · followsMe. 기록을 볼 수 있어야 목록도 본다.")
	@GetMapping("/bread-records/{recordId}/likes")
	public ApiResponse<SliceResponse<FollowMemberResponse>> getLikers(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long recordId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(breadRecordSocialQueryService.getLikers(viewerId, recordId, page, size));
	}

	@Operation(summary = "프로필의 공개 빵로그", description = "sort=RECOMMENDED(기본) | LATEST, offset. 상대가 게스트 · 봇 · 탈퇴면 404 "
		+ "PROFILE404_MEMBER, 차단 관계면 403 PROFILE403_BLOCKED. 본인은 숨긴 기록도 hiddenStatus 와 함께 본다.")
	@GetMapping("/members/{memberId}/bread-records")
	public ApiResponse<SliceResponse<MemberBreadRecordResponse>> getMemberRecords(
			@AuthenticationPrincipal Long viewerId,
			@PathVariable Long memberId,
			@RequestParam(defaultValue = "RECOMMENDED") BreadRecordSort sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(breadRecordSocialQueryService.getMemberRecords(viewerId, memberId, sort, page, size));
	}
}
