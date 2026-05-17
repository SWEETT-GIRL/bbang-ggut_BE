package sweetgirl.croissant.banner.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.banner.dto.response.BannerDetailResponse;
import sweetgirl.croissant.banner.dto.response.BannerSummaryResponse;
import sweetgirl.croissant.banner.service.BannerService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Banner", description = "배너 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/banners")
public class BannerController {

	private final BannerService bannerService;

	@Operation(summary = "배너 목록 조회", description = "노출 순서대로 배너 목록을 반환합니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<BannerSummaryResponse>>> getBanners() {
		return ResponseEntity.ok(ApiResponse.ok(bannerService.getBanners()));
	}

	@Operation(summary = "배너 상세 조회", description = "배너 클릭 시 보여줄 상세 이미지를 반환합니다.")
	@GetMapping("/{bannerId}")
	public ResponseEntity<ApiResponse<BannerDetailResponse>> getBannerDetail(
			@Parameter(description = "배너 ID") @PathVariable Long bannerId) {
		return ResponseEntity.ok(ApiResponse.ok(bannerService.getBannerDetail(bannerId)));
	}
}
