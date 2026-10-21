package sweetgirl.croissant.admin.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.service.AdminCourseCommandService;
import sweetgirl.croissant.admin.service.AdminCourseQueryService;
import sweetgirl.croissant.course.dto.request.AdminCourseCreateRequest;
import sweetgirl.croissant.course.dto.request.AdminCoursePinRequest;
import sweetgirl.croissant.course.dto.request.AdminCourseUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreUpdateRequest;
import sweetgirl.croissant.course.dto.response.AdminCourseDetailResponse;
import sweetgirl.croissant.course.dto.response.AdminCourseResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Admin Course", description = "어드민 코스 관리 API")
@RestController
@RequestMapping("/api/admin/courses")
@RequiredArgsConstructor
public class AdminCourseController {

	private final AdminCourseQueryService adminCourseQueryService;
	private final AdminCourseCommandService adminCourseCommandService;

	// ───────────────────────── 조회 ─────────────────────────

	@Operation(summary = "CURATED 코스 목록 조회", description = "삭제되지 않은 CURATED 코스를 최신순으로 반환합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<AdminCourseResponse>>> getCourses() {
		return ResponseEntity.ok(ApiResponse.ok(adminCourseQueryService.getCuratedCourses()));
	}

	@Operation(summary = "코스 상세 조회", description = "코스 상세 정보와 포함된 가게 목록을 반환합니다.")
	@GetMapping("/{courseId}")
	public ResponseEntity<ApiResponse<AdminCourseDetailResponse>> getCourseDetail(
			@Parameter(description = "코스 ID") @PathVariable Long courseId) {
		return ResponseEntity.ok(ApiResponse.ok(adminCourseQueryService.getCuratedCourseDetail(courseId)));
	}

	// ───────────────────────── CRUD ─────────────────────────

	@Operation(summary = "CURATED 코스 생성", description = "어드민이 CURATED 코스를 생성합니다. member=null, isPublic=true 고정.")
	@PostMapping
	public ResponseEntity<ApiResponse<Map<String, Long>>> createCourse(
			@Valid @RequestBody AdminCourseCreateRequest request) {
		Long courseId = adminCourseCommandService.createCuratedCourse(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(Map.of("courseId", courseId)));
	}

	@Operation(summary = "코스 수정", description = "코스 제목·뱃지를 partial update합니다.")
	@PatchMapping("/{courseId}")
	public ResponseEntity<ApiResponse<Void>> updateCourse(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Valid @RequestBody AdminCourseUpdateRequest request) {
		adminCourseCommandService.updateCuratedCourse(courseId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 삭제", description = "코스를 soft delete합니다.")
	@DeleteMapping("/{courseId}")
	public ResponseEntity<ApiResponse<Void>> deleteCourse(
			@Parameter(description = "코스 ID") @PathVariable Long courseId) {
		adminCourseCommandService.deleteCuratedCourse(courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	// ───────────────────────── 이미지 ─────────────────────────

	@Operation(summary = "코스 이미지 Presigned URL 발급", description = "S3 업로드용 Presigned URL을 발급합니다.")
	@PostMapping("/{courseId}/images/presigned-url")
	public ResponseEntity<ApiResponse<PresignedUrlResponse>> getImagePresignedUrl(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Parameter(description = "MIME 타입 (e.g. image/jpeg)") @RequestParam String mimeType) {
		return ResponseEntity.ok(ApiResponse.ok(adminCourseCommandService.getPresignedUrl(courseId, mimeType)));
	}

	@Operation(summary = "코스 이미지 등록", description = "S3 업로드 완료 후 이미지 정보를 DB에 저장합니다. 코스당 1개.")
	@PostMapping("/{courseId}/images")
	public ResponseEntity<ApiResponse<Void>> registerImage(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Valid @RequestBody CourseImageRegisterRequest request) {
		adminCourseCommandService.registerImage(courseId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 이미지 삭제", description = "코스 이미지를 S3와 DB에서 함께 삭제합니다.")
	@DeleteMapping("/{courseId}/images")
	public ResponseEntity<ApiResponse<Void>> deleteImage(
			@Parameter(description = "코스 ID") @PathVariable Long courseId) {
		adminCourseCommandService.deleteImage(courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	// ───────────────────────── 가게 관리 ─────────────────────────

	@Operation(summary = "코스 가게 추가", description = "코스에 가게를 추가합니다. visitOrder는 자동 부여되며 중복 추가는 불가합니다.")
	@PostMapping("/{courseId}/stores")
	public ResponseEntity<ApiResponse<CourseStoreResponse>> addStore(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Valid @RequestBody CourseStoreAddRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(adminCourseCommandService.addStore(courseId, request)));
	}

	@Operation(summary = "코스 가게 수정", description = "코스 가게의 메모·방문 시간을 수정합니다.")
	@PatchMapping("/{courseId}/stores/{storeId}")
	public ResponseEntity<ApiResponse<Void>> updateStore(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Parameter(description = "가게 ID") @PathVariable Long storeId,
			@RequestBody CourseStoreUpdateRequest request) {
		adminCourseCommandService.updateStore(courseId, storeId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 가게 삭제", description = "코스에서 가게를 soft delete합니다.")
	@DeleteMapping("/{courseId}/stores/{storeId}")
	public ResponseEntity<ApiResponse<Void>> removeStore(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Parameter(description = "가게 ID") @PathVariable Long storeId) {
		adminCourseCommandService.removeStore(courseId, storeId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 가게 순서 변경", description = "courseStoreIds 배열 순서를 새 visitOrder로 적용합니다.")
	@PatchMapping("/{courseId}/stores/reorder")
	public ResponseEntity<ApiResponse<Void>> reorderStores(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Valid @RequestBody CourseStoreReorderRequest request) {
		adminCourseCommandService.reorderStores(courseId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	// ───────────────────────── 핀 ─────────────────────────

	@Operation(summary = "코스 핀 등록", description = "CURATED 코스를 홈 핀 영역에 등록합니다. 최대 3개, 순위 중복 불가.")
	@PostMapping("/{courseId}/pin")
	public ResponseEntity<ApiResponse<Void>> pinCourse(
			@Parameter(description = "코스 ID") @PathVariable Long courseId,
			@Valid @RequestBody AdminCoursePinRequest request) {
		adminCourseCommandService.pinCourse(courseId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "코스 핀 해제", description = "코스의 핀 등록을 해제합니다.")
	@DeleteMapping("/{courseId}/pin")
	public ResponseEntity<ApiResponse<Void>> unpinCourse(
			@Parameter(description = "코스 ID") @PathVariable Long courseId) {
		adminCourseCommandService.unpinCourse(courseId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
