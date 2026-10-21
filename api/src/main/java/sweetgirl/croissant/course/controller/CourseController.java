package sweetgirl.croissant.course.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.dto.request.CourseCreateRequest;
import sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest;
import sweetgirl.croissant.course.dto.request.CourseMemberNameRequest;
import sweetgirl.croissant.course.dto.request.CourseQuickVisitRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreVisitRequest;
import sweetgirl.croissant.course.dto.request.CourseUpdateRequest;
import sweetgirl.croissant.course.dto.response.CourseDetailLookup;
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseInviteAcceptResponse;
import sweetgirl.croissant.course.dto.response.CourseInvitePreviewResponse;
import sweetgirl.croissant.course.dto.response.CourseInviteResponse;
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.dto.response.CourseUpdateResponse;
import sweetgirl.croissant.course.dto.response.CourseVisitCandidateResponse;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.course.service.CourseMemberService;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.course.service.CourseQueryService;
import sweetgirl.croissant.course.service.CourseStoreVisitService;
import sweetgirl.croissant.course.service.SavedCourseCommandService;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.response.TourRewardResponse;

@Tag(name = "Course", description = "코스 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/courses")
public class CourseController {

	private final CourseQueryService courseQueryService;
	private final CourseCommandService courseCommandService;
	private final SavedCourseCommandService savedCourseCommandService;
	private final CourseProgressService courseProgressService;
	private final CourseStoreVisitService courseStoreVisitService;
	private final CourseMemberService courseMemberService;

	@Operation(summary = "공개 코스 목록 조회")
	@GetMapping
	public ResponseEntity<ApiResponse<List<CourseSummaryResponse>>> getCourses() {
		return ResponseEntity.ok(ApiResponse.ok(courseQueryService.getCurationCourses()));
	}

	@Operation(summary = "내 코스 목록 조회")
	@GetMapping("/my")
	public ResponseEntity<ApiResponse<List<CourseSummaryResponse>>> getMyCourses(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(courseQueryService.getMyCourses(memberId)));
	}

	@Operation(summary = "찜한 코스 목록 조회")
	@GetMapping("/saved")
	public ResponseEntity<ApiResponse<List<CourseSummaryResponse>>> getSavedCourses(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(courseQueryService.getSavedCourses(memberId)));
	}

	@Operation(summary = "진행 중인 코스 목록 조회")
	@GetMapping("/progress")
	public ResponseEntity<ApiResponse<List<CourseProgressResponse>>> getCourseProgresses(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(courseProgressService.getOngoing(memberId)));
	}

	@Operation(summary = "코스 상세 조회", description = "If-None-Match 가 ETag(version)와 같으면 304. 링크 공개는 ?t= 토큰으로 본다.")
	@GetMapping("/{courseId}")
	public ResponseEntity<ApiResponse<CourseDetailResponse>> getCourseDetail(
			@PathVariable Long courseId,
			@AuthenticationPrincipal Long memberId,
			@RequestParam(name = "t", required = false) String shareToken,
			WebRequest request) {
		// ETag 는 version 만 쓴다(본문 해시 아님). 서비스가 권한 검사를 먼저 하고 version 을 넘긴다 — 권한 없는 사람에게
		// 304 로 존재를 알려주면 안 된다. 코스는 한 번만 읽고, ETag 가 같으면 본문을 만들지 않는다.
		CourseDetailLookup lookup = courseQueryService.getCourseDetail(courseId, memberId, shareToken,
			version -> request.checkNotModified(etagOf(version)));
		String etag = etagOf(lookup.version());
		// 본문은 사람마다 다르다(isMine·visited·myMemo) — 중간 캐시가 끼지 않게 private, no-cache
		CacheControl cacheControl = CacheControl.noCache().cachePrivate();
		if (lookup.body().isEmpty()) {
			// checkNotModified 가 이미 304 상태와 ETag 를 응답에 썼다. 본문 없는 응답을 명시적으로 돌려준다
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
				.eTag(etag)
				.cacheControl(cacheControl)
				.build();
		}
		return ResponseEntity.ok()
			.eTag(etag)
			.cacheControl(cacheControl)
			.body(ApiResponse.ok(lookup.body().get()));
	}

	private static String etagOf(long version) {
		return "\"" + version + "\"";
	}

	@Operation(summary = "공유 토큰으로 코스 상세 조회", description = "랜딩 /c/{shareToken} 용. 비로그인 허용. 링크 공개가 아니면 404.")
	@GetMapping("/by-share/{shareToken}")
	public ResponseEntity<ApiResponse<CourseDetailResponse>> getCourseDetailByShareToken(
			@PathVariable String shareToken,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(courseQueryService.getCourseDetailByShareToken(shareToken, memberId)));
	}

	@Operation(summary = "코스 생성")
	@PostMapping
	public ResponseEntity<ApiResponse<Map<String, Long>>> createCourse(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody CourseCreateRequest request) {
		Long courseId = courseCommandService.createCourse(memberId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of("courseId", courseId)));
	}

	@Operation(summary = "코스 수정", description = "공개 범위(visibility) 전환은 방장 전용. 링크 공개로 처음 바꾸면 shareToken 을 발급한다.")
	@PatchMapping("/{courseId}")
	public ResponseEntity<ApiResponse<CourseUpdateResponse>> updateCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseUpdateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(courseCommandService.updateCourse(memberId, courseId, request)));
	}

	@Operation(summary = "코스 삭제")
	@DeleteMapping("/{courseId}")
	public ResponseEntity<ApiResponse<Void>> deleteCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		courseCommandService.deleteCourse(memberId, courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 이미지 presigned URL 발급")
	@PostMapping("/{courseId}/images/presigned-url")
	public ResponseEntity<ApiResponse<PresignedUrlResponse>> getPresignedUrl(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@RequestParam String mimeType) {
		return ResponseEntity.ok(ApiResponse.ok(courseCommandService.getPresignedUrl(memberId, courseId, mimeType)));
	}

	@Operation(summary = "코스 이미지 등록")
	@PostMapping("/{courseId}/images")
	public ResponseEntity<ApiResponse<Void>> registerImage(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseImageRegisterRequest request) {
		courseCommandService.registerImage(memberId, courseId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 이미지 삭제")
	@DeleteMapping("/{courseId}/images")
	public ResponseEntity<ApiResponse<Void>> deleteImage(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		courseCommandService.deleteImage(memberId, courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 가게 추가")
	@PostMapping("/{courseId}/stores")
	public ResponseEntity<ApiResponse<CourseStoreResponse>> addStore(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseStoreAddRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(courseCommandService.addStore(memberId, courseId, request)));
	}

	@Operation(summary = "코스 가게 수정")
	@PatchMapping("/{courseId}/stores/{storeId}")
	public ResponseEntity<ApiResponse<Void>> updateStore(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId,
			@Valid @RequestBody CourseStoreUpdateRequest request) {
		courseCommandService.updateStore(memberId, courseId, storeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 가게 제거")
	@DeleteMapping("/{courseId}/stores/{storeId}")
	public ResponseEntity<ApiResponse<Void>> removeStore(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId) {
		courseCommandService.removeStore(memberId, courseId, storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 가게 다녀왔어요 표시", description = "빵로그 기록 하나를 골라 이 코스의 방문으로 잇는다.")
	@PostMapping("/{courseId}/stores/{storeId}/visit")
	public ResponseEntity<ApiResponse<TourRewardResponse>> markVisited(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId,
			@Valid @RequestBody CourseStoreVisitRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			courseStoreVisitService.markVisited(memberId, courseId, storeId, request.getBreadRecordId())));
	}

	@Operation(summary = "코스 가게 한 번 탭으로 다녀왔어요", description = "가게만 있는 가벼운 빵로그 기록을 만들고 이 코스의 방문으로 잇는다. "
		+ "이미 표시한 가게면 기록을 또 만들지 않는다. 기록에는 나중에 빵과 사진을 덧붙일 수 있다.")
	@PostMapping("/{courseId}/stores/{storeId}/visit-quick")
	public ResponseEntity<ApiResponse<TourRewardResponse>> markVisitedQuick(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId,
			@RequestBody(required = false) CourseQuickVisitRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(courseStoreVisitService.markVisitedQuick(
			memberId, courseId, storeId, request == null ? null : request.requestId())));
	}

	@Operation(summary = "코스 가게 다녀왔어요 해제")
	@DeleteMapping("/{courseId}/stores/{storeId}/visit")
	public ResponseEntity<ApiResponse<Void>> unmarkVisited(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId) {
		courseStoreVisitService.unmarkVisited(memberId, courseId, storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "다녀왔어요로 고를 수 있는 내 기록 목록", description = "그 가게의 내 기록 전부를 최신순으로 준다.")
	@GetMapping("/{courseId}/stores/{storeId}/records")
	public ResponseEntity<ApiResponse<List<CourseVisitCandidateResponse>>> getVisitCandidates(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId) {
		return ResponseEntity.ok(ApiResponse.ok(
			courseStoreVisitService.getCandidates(memberId, courseId, storeId)));
	}

	@Operation(summary = "코스 가게 순서 변경")
	@PatchMapping("/{courseId}/stores/reorder")
	public ResponseEntity<ApiResponse<Void>> reorderStores(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseStoreReorderRequest request) {
		courseCommandService.reorderStores(memberId, courseId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 찜 추가")
	@PostMapping("/{courseId}/save")
	public ResponseEntity<ApiResponse<Void>> saveCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		savedCourseCommandService.saveCourse(memberId, courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 찜 해제")
	@DeleteMapping("/{courseId}/save")
	public ResponseEntity<ApiResponse<Void>> unsaveCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		savedCourseCommandService.unsaveCourse(memberId, courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 따라가기 시작")
	@PostMapping("/{courseId}/progress")
	public ResponseEntity<ApiResponse<Void>> startCourseProgress(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		courseProgressService.start(memberId, courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 따라가기 중단")
	@DeleteMapping("/{courseId}/progress")
	public ResponseEntity<ApiResponse<Void>> stopCourseProgress(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		courseProgressService.stop(memberId, courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 포크", description = "링크 공개 코스는 ?t= 토큰이 맞아야 포크할 수 있다.")
	@PostMapping("/{courseId}/fork")
	public ResponseEntity<ApiResponse<Map<String, Long>>> forkCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@RequestParam(name = "t", required = false) String shareToken) {
		Long newCourseId = courseCommandService.forkCourse(memberId, courseId, shareToken);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of("courseId", newCourseId)));
	}

	// ───────────────────────── 동시편집: 초대·멤버 ─────────────────────────

	@Operation(summary = "초대 링크 만들기·받기", description = "방장 또는 멤버. 하루 이상 남은 링크는 재사용(reused), 아니면 새로 발급한다.")
	@PostMapping("/{courseId}/invites")
	public ResponseEntity<ApiResponse<CourseInviteResponse>> createInvite(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		return ResponseEntity.ok(ApiResponse.ok(courseMemberService.createInvite(memberId, courseId)));
	}

	@Operation(summary = "초대 미리보기", description = "비로그인 허용. 만료·무효·정원초과·삭제도 state 로 200. viewer 는 인증 헤더가 있을 때만.")
	@GetMapping("/invites/{token}")
	public ResponseEntity<ApiResponse<CourseInvitePreviewResponse>> previewInvite(
			@PathVariable String token,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(courseMemberService.previewInvite(token, memberId)));
	}

	@Operation(summary = "초대 수락", description = "로그인 필수. 이미 멤버이거나 방장이면 아무것도 안 하고 alreadyMember 로 200.")
	@PostMapping("/invites/{token}/accept")
	public ResponseEntity<ApiResponse<CourseInviteAcceptResponse>> acceptInvite(
			@AuthenticationPrincipal Long memberId,
			@PathVariable String token,
			@Valid @RequestBody CourseMemberNameRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			courseMemberService.acceptInvite(memberId, token, request.getDisplayName())));
	}

	@Operation(summary = "빵투어 나가기", description = "방장은 참여 순서가 가장 빠른 멤버에게 넘기고 나간다. 담았던 가게는 코스에 남는다.")
	@DeleteMapping("/{courseId}/members/me")
	public ResponseEntity<ApiResponse<Void>> leaveCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		courseMemberService.leave(memberId, courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 안에서 쓰는 내 이름 변경")
	@PatchMapping("/{courseId}/members/me")
	public ResponseEntity<ApiResponse<Void>> renameMember(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseMemberNameRequest request) {
		courseMemberService.rename(memberId, courseId, request.getDisplayName());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
