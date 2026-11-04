package sweetgirl.croissant.admin.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminStoreTrendingRefreshResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.store.service.StoreTrendingBaselineCommandService;
import sweetgirl.croissant.store.service.trending.TrendingRankingService;

/** 자동완성 급부상 수동 갱신 — 매일 배치(평소 수준)·5분 주기(순위표)를 기다리지 않고 바로 반영할 때. */
@RestController
@RequestMapping("/admin/search/trending")
@RequiredArgsConstructor
@Tag(name = "Admin Search", description = "검색 데이터 관리")
public class AdminStoreTrendingController {

	private final StoreTrendingBaselineCommandService baselineCommandService;
	private final TrendingRankingService rankingService;

	@PostMapping("/baseline/refresh")
	@Operation(summary = "가게 평소 수준 갱신", description = "최근 7일 바로 앞 28일의 신호(조회·찜·가고싶은 가게·코스담기·빵로그)로 "
		+ "store.trending_baseline 을 다시 계산하고 자동완성 캐시를 비웁니다.")
	public ApiResponse<AdminStoreTrendingRefreshResponse> refreshBaselines() {
		return ApiResponse.ok(new AdminStoreTrendingRefreshResponse(
			baselineCommandService.refreshBaselines(LocalDate.now())));
	}

	@PostMapping("/ranking/refresh")
	@Operation(summary = "급부상 순위표 갱신", description = "Redis 의 최근 7일 신호로 순위표(trend:ranking)를 바로 다시 만듭니다.")
	public ApiResponse<AdminStoreTrendingRefreshResponse> refreshRanking() {
		return ApiResponse.ok(new AdminStoreTrendingRefreshResponse(rankingService.refresh(LocalDateTime.now())));
	}
}
