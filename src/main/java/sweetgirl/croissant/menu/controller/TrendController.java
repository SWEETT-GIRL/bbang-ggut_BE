package sweetgirl.croissant.menu.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.menu.dto.response.RegionTrendResponse;
import sweetgirl.croissant.menu.service.TrendService;

@Tag(name = "Trend", description = "지역 트렌드 API")
@RestController
@RequestMapping("/trend")
@RequiredArgsConstructor
public class TrendController {

	private final TrendService trendService;

	@Operation(summary = "내 위치 기반 유명 메뉴 & 핫 빵집 조회", description = "위/경도를 받아 해당 지역의 유명 디저트 카테고리와 핫 빵집 목록을 반환합니다.")
	@GetMapping("/nearby")
	public ResponseEntity<ApiResponse<RegionTrendResponse>> getNearbyTrend(
			@RequestParam double lat,
			@RequestParam double lng) {
		return ResponseEntity.ok(ApiResponse.ok(trendService.getTrend(lat, lng)));
	}

	@Operation(summary = "지역 선택 기반 유명 메뉴 & 핫 빵집 조회")
	@GetMapping("/select")
	public ResponseEntity<ApiResponse<RegionTrendResponse>> getSelectedTrend(
			@RequestParam String region) {
		return ResponseEntity.ok(ApiResponse.ok(trendService.getTrendByRegion(region)));
	}
}
