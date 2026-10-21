package sweetgirl.croissant.dessert.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.util.MapBounds;
import sweetgirl.croissant.dessert.dto.response.DessertStoreResponse;
import sweetgirl.croissant.dessert.dto.response.HotDessertResponse;
import sweetgirl.croissant.dessert.service.DessertQueryService;
import sweetgirl.croissant.global.response.ApiResponse;

@Tag(name = "Dessert", description = "요즘 뜨는 디저트 API")
@RestController
@RequestMapping("/desserts")
@RequiredArgsConstructor
public class DessertController {

	/** 반경으로 찾을 때의 기본 개수 — 영역 인자가 생기기 전 기본값(30)을 그대로 지킨다 */
	private static final int DEFAULT_SIZE = 30;

	private final DessertQueryService dessertQueryService;

	@Operation(summary = "요즘 뜨는 디저트 목록 조회", description = "지도 칩 줄에 세울 디저트를 뜨는 순서대로 반환합니다. 노출 대상(is_active)만 나옵니다.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<HotDessertResponse>>> getHotDesserts() {
		return ResponseEntity.ok(ApiResponse.ok(dessertQueryService.getHotDesserts()));
	}

	@Operation(summary = "디저트 판매처 조회", description = "이 디저트를 파는 곳을 가까운 순으로 반환합니다. "
		+ "편의점 상품은 해당 브랜드의 지점을, 나머지는 빵긋 가게를 반환하며 응답 모양은 같습니다. "
		+ "swLat·swLng·neLat·neLng 를 함께 주면 반경 대신 그 지도 영역 안만 반환합니다(size 기본 150, 최대 300).")
	@GetMapping("/{dessertItemId}/stores")
	public ResponseEntity<ApiResponse<List<DessertStoreResponse>>> getSellingStores(
			@PathVariable Long dessertItemId,
			@RequestParam double lat,
			@RequestParam double lng,
			@Parameter(description = "기본 30·최대 50 (영역을 주면 기본 150·최대 300)") @RequestParam(required = false) Integer size,
			@Parameter(description = "반경(m) 기본 3만·최대 5만, 영역 주면 무시") @RequestParam(required = false) Double radiusMeters,
			@Parameter(description = "지도 영역 남서 위도") @RequestParam(required = false) Double swLat,
			@Parameter(description = "지도 영역 남서 경도") @RequestParam(required = false) Double swLng,
			@Parameter(description = "지도 영역 북동 위도") @RequestParam(required = false) Double neLat,
			@Parameter(description = "지도 영역 북동 경도") @RequestParam(required = false) Double neLng) {
		List<DessertStoreResponse> sellingStores = MapBounds.ofNullable(swLat, swLng, neLat, neLng)
			.map(bounds -> dessertQueryService.getSellingStoresInBounds(dessertItemId, bounds, lat, lng, size))
			.orElseGet(() -> dessertQueryService.getSellingStores(dessertItemId, lat, lng,
				size == null ? DEFAULT_SIZE : size, radiusMeters));
		return ResponseEntity.ok(ApiResponse.ok(sellingStores));
	}
}
