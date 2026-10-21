package sweetgirl.croissant.breadlog.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.PopularBreadResponse;
import sweetgirl.croissant.breadlog.service.PopularBreadQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "[Client] 빵 인기 API", description = "요즘 많이 기록된 빵")
@RestController
@RequiredArgsConstructor
@RequestMapping("/breads")
public class PopularBreadController {

	private final PopularBreadQueryService popularBreadQueryService;

	@Operation(summary = "요즘 많이 기록된 빵", description = "최근 30일 기록의 빵 태그를 분류별로 세어 많이 기록된 순으로 돌려줍니다. "
		+ "분류를 못 정한 '빵'·'기타'는 빠집니다. size 는 1~20(기본 3).")
	@GetMapping("/popular")
	public ResponseEntity<ApiResponse<List<PopularBreadResponse>>> getPopularBreads(
			@Parameter(description = "몇 개까지(1~20)") @RequestParam(defaultValue = "3") int size) {
		return ResponseEntity.ok(ApiResponse.ok(popularBreadQueryService.getPopularBreads(size)));
	}
}
