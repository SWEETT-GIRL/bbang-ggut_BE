package sweetgirl.croissant.course.controller;

import java.util.List;
import java.util.Map;

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.dto.request.CourseCreateRequest;
import sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreVisitRequest;
import sweetgirl.croissant.course.dto.request.CourseUpdateRequest;
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.dto.response.CourseVisitCandidateResponse;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.course.service.CourseQueryService;
import sweetgirl.croissant.course.service.CourseStoreVisitService;
import sweetgirl.croissant.course.service.SavedCourseCommandService;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;

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

	@Operation(summary = "코스 상세 조회")
	@GetMapping("/{courseId}")
	public ResponseEntity<ApiResponse<CourseDetailResponse>> getCourseDetail(
			@PathVariable Long courseId,
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(courseQueryService.getCourseDetail(courseId, memberId)));
	}

	@Operation(summary = "코스 생성")
	@PostMapping
	public ResponseEntity<ApiResponse<Map<String, Long>>> createCourse(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody CourseCreateRequest request) {
		Long courseId = courseCommandService.createCourse(memberId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of("courseId", courseId)));
	}

	@Operation(summary = "코스 수정")
	@PatchMapping("/{courseId}")
	public ResponseEntity<ApiResponse<Void>> updateCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@Valid @RequestBody CourseUpdateRequest request) {
		courseCommandService.updateCourse(memberId, courseId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
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
	public ResponseEntity<ApiResponse<Void>> markVisited(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId,
			@PathVariable Long storeId,
			@Valid @RequestBody CourseStoreVisitRequest request) {
		courseStoreVisitService.markVisited(memberId, courseId, storeId, request.getBreadRecordId());
		return ResponseEntity.ok(ApiResponse.ok(null));
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

	@Operation(summary = "코스 포크")
	@PostMapping("/{courseId}/fork")
	public ResponseEntity<ApiResponse<Map<String, Long>>> forkCourse(
			@AuthenticationPrincipal Long memberId,
			@PathVariable Long courseId) {
		Long newCourseId = courseCommandService.forkCourse(memberId, courseId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of("courseId", newCourseId)));
	}
}
