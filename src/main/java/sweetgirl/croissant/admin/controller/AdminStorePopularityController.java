package sweetgirl.croissant.admin.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminStorePopularityRefreshResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.service.StorePopularityCommandService;

/** 가게 인기도(store.popularity_score) 수동 갱신 — 주간 배치를 기다리지 않고 바로 반영할 때. */
@RestController
@RequestMapping("/admin/search/popularity")
@RequiredArgsConstructor
@Tag(name = "Admin Search", description = "검색 데이터 관리")
public class AdminStorePopularityController {

	private final StorePopularityCommandService storePopularityCommandService;

	@PostMapping("/refresh")
	@Operation(summary = "가게 인기도 전체 갱신", description = "구독수·전국 랭킹·조회수로 store.popularity_score 를 다시 계산합니다.")
	public ApiResponse<AdminStorePopularityRefreshResponse> refreshPopularityScores() {
		int updated = storePopularityCommandService.refreshAllPopularityScores();
		return ApiResponse.ok(new AdminStorePopularityRefreshResponse(updated));
	}
}
