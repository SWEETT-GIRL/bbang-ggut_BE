package sweetgirl.croissant.breadranking.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadranking.dto.request.BreadCategoryCreateRequest;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryCreateResult;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.service.BreadCategoryCommandService;
import sweetgirl.croissant.breadranking.service.BreadCategoryQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "BreadRanking", description = "빵 랭킹 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/bread-categories")
public class BreadCategoryController {

	private final BreadCategoryQueryService categoryQueryService;
	private final BreadCategoryCommandService categoryCommandService;

	@Operation(summary = "빵 종류 공용 목록", description = "기본 목록 + 내가 만든 것 + 서로 다른 3명 이상이 탭에 넣은 사용자 생성 종류. "
		+ "기본 8종(소금빵·케이크·사워도우·휘낭시에·샌드베이글·에그타르트·크루아상·도넛)이 그 순서로 먼저, 나머지는 1등 매긴 사람 수(voters) 많은 순. 게스트 OK.")
	@GetMapping
	public ResponseEntity<ApiResponse<java.util.List<BreadCategoryResponse>>> getCategories(
			@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(categoryQueryService.getCategories(memberId)));
	}

	@Operation(summary = "빵 종류 직접 만들기", description = "2~15자. 같은 이름(공백·기호 무시)이 있으면 새로 만들지 않고 200 으로 그걸 준다 — "
		+ "새로 만들면 201. 부적절한 표현은 400 BREAD_CATEGORY_NAME_REJECTED. 게스트는 403.")
	@PostMapping
	public ResponseEntity<ApiResponse<BreadCategoryResponse>> create(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody BreadCategoryCreateRequest request) {
		BreadCategoryCreateResult result = categoryCommandService.create(memberId, request.name());
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.ok(result.category()));
	}
}
