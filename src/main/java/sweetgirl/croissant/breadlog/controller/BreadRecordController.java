package sweetgirl.croissant.breadlog.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
import sweetgirl.croissant.breadlog.dto.request.BreadPhotoAnalyzeRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPresignedUrlRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordVisibilityRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlInfo;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordSummaryResponse;
import sweetgirl.croissant.breadlog.service.BreadPhotoTagSuggestionService;
import sweetgirl.croissant.breadlog.service.BreadRecordCommandService;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.breadlog.service.BreadRecordSummaryService;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;

@Tag(name = "BreadLog", description = "빵 기록 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/bread-records")
public class BreadRecordController {

	private final BreadRecordQueryService queryService;
	private final BreadRecordCommandService commandService;
	private final BreadRecordSummaryService summaryService;
	private final BreadPhotoTagSuggestionService photoTagSuggestionService;

	@Operation(summary = "내 빵 기록 목록 조회", description = "기간은 eatenAt 기준이며 from 이상, to 미만으로 조회합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<SliceResponse<BreadRecordResponse>>> getRecords(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
			@RequestParam(required = false) Long storeId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "30") int size) {
		return ResponseEntity.ok(ApiResponse.ok(queryService.getRecords(memberId, from, to, storeId, page, size)));
	}

	@Operation(summary = "내 빵 기록 집계 조회", description = "기간은 eatenAt 기준이며 from 이상, to 미만으로 집계합니다.")
	@GetMapping("/summary")
	public ResponseEntity<ApiResponse<BreadRecordSummaryResponse>> getSummary(
			@AuthenticationPrincipal Long memberId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
		return ResponseEntity.ok(ApiResponse.ok(summaryService.getSummary(memberId, from, to)));
	}

	@Operation(summary = "빵 기록 생성")
	@PostMapping
	public ResponseEntity<ApiResponse<BreadRecordCreateResponse>> create(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody BreadRecordUpsertRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.created(commandService.create(memberId, request)));
	}

	@Operation(summary = "빵 기록 전체 교체", description = "사진·태그·메모·가게·먹은 날을 요청 스냅샷으로 교체합니다.")
	@PatchMapping("/{recordId}")
	public ResponseEntity<ApiResponse<Void>> replace(
			@PathVariable Long recordId,
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody BreadRecordUpsertRequest request) {
		commandService.replace(recordId, memberId, request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "빵 기록 공개 여부 변경", description = "공개하면 가게 상세의 빵로그 목록에 보입니다. "
		+ "사진·태그는 건드리지 않고 공개 여부만 바꿉니다.")
	@PatchMapping("/{recordId}/visibility")
	public ResponseEntity<ApiResponse<Void>> changeVisibility(
			@PathVariable Long recordId,
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody BreadRecordVisibilityRequest request) {
		commandService.changeVisibility(recordId, memberId, request.isPublic());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "빵 기록 삭제")
	@DeleteMapping("/{recordId}")
	public ResponseEntity<ApiResponse<Void>> delete(
			@PathVariable Long recordId,
			@AuthenticationPrincipal Long memberId) {
		commandService.delete(recordId, memberId);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "빵 기록 사진 AI 태그 제안", description = "사진에 찍힌 빵을 그 가게 메뉴 중에서 골라 이름과 위치를 제안합니다. "
		+ "사진은 분석에만 쓰고 저장하지 않습니다. 제안이 없거나 기능이 꺼져 있으면 빈 목록을 돌려줍니다.")
	@PostMapping(value = "/photos/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ApiResponse<BreadPhotoAnalyzeResponse>> analyzePhotos(
			@AuthenticationPrincipal Long memberId,
			@Valid @ModelAttribute BreadPhotoAnalyzeRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			photoTagSuggestionService.analyzeForMember(memberId, request.storeId(), request.toPhotos())));
	}

	@Operation(summary = "빵 기록 이미지 Presigned URL 단건 발급")
	@GetMapping("/images/presigned-url")
	public ResponseEntity<ApiResponse<BreadRecordPresignedUrlInfo>> getPresignedUrl(
			@AuthenticationPrincipal Long memberId,
			@RequestParam String mimeType) {
		BreadRecordPresignedUrlInfo response = commandService.generatePresignedUrls(
			memberId,
			new BreadRecordPresignedUrlRequest(List.of(mimeType)))
			.presignedUrls()
			.get(0);
		return ResponseEntity.ok(ApiResponse.ok(response));
	}

	@Operation(summary = "빵 기록 이미지 Presigned URL 일괄 발급")
	@PostMapping("/images/presigned-urls")
	public ResponseEntity<ApiResponse<BreadRecordPresignedUrlResponse>> getPresignedUrls(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody BreadRecordPresignedUrlRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(commandService.generatePresignedUrls(memberId, request)));
	}
}
