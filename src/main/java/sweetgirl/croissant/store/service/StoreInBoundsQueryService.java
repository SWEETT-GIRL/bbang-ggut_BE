package sweetgirl.croissant.store.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.util.MapBounds;
import sweetgirl.croissant.store.dto.projection.StoreInBoundsProjection;
import sweetgirl.croissant.store.dto.response.StoresInBoundsResponse;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.service.search.StoreSearchAssembler;
import sweetgirl.croissant.store.service.search.StoreSearchHit;

/**
 * 지도 영역 안 가게. 전엔 앱이 시·도 이름으로 거리순 검색을 칸마다 불러 합쳤다 —
 * 도 경계 너머 가게가 빠지고, 멀리 빠지면 요청이 9번 나갔다. 이제 보이는 사각형을 한 번에 묻는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreInBoundsQueryService {

	public static final int DEFAULT_LIMIT = 150;
	/** 비로그인 엔드포인트라 상한을 둔다. 한 화면에 이보다 많으면 핀이 지도를 덮는다 */
	public static final int MAX_LIMIT = 300;
	/**
	 * total 을 세는 상한. 앱은 total 을 "N곳 중 M곳"에만 쓰고 지금 앱(1.4.x)은 읽지도 않는다 — 전국을 띄워도
	 * 영역 안 가게를 끝까지 세지 않게 여기서 멈춘다. MAX_LIMIT 보다 커서 "content 보다 크다"는 뜻은 지켜진다.
	 */
	public static final int TOTAL_CAP = 1000;

	private final StoreRepository storeRepository;
	private final StoreSearchAssembler storeSearchAssembler;

	/**
	 * @param latitude  가까운 순의 기준점(보통 내 위치). 없으면 영역 가운데.
	 * @param longitude latitude 와 함께 주거나 함께 뺀다.
	 */
	public StoresInBoundsResponse getStoresInBounds(MapBounds bounds, Double latitude, Double longitude,
			Integer limit) {
		if ((latitude == null) != (longitude == null)) {
			throw new IllegalArgumentException("lat 과 lng 는 함께 주어야 합니다");
		}
		double centerLat = latitude != null ? latitude : bounds.centerLat();
		double centerLng = longitude != null ? longitude : bounds.centerLng();
		if (centerLat < -90 || centerLat > 90 || centerLng < -180 || centerLng > 180) {
			throw new IllegalArgumentException("lat 은 -90~90, lng 는 -180~180 이어야 합니다");
		}

		int clampedLimit = clampLimit(limit);
		List<StoreInBoundsProjection> rows = storeRepository.findStoresInBounds(
			bounds.swLat(), bounds.swLng(), bounds.neLat(), bounds.neLng(), centerLat, centerLng, clampedLimit);
		if (rows.isEmpty()) {
			return new StoresInBoundsResponse(List.of(), 0);
		}
		// limit 에 못 미쳤으면 영역 안이 전부 온 것이다 — 따로 셀 필요가 없다
		long total = rows.size() < clampedLimit
			? rows.size()
			: storeRepository.countStoresInBounds(
				bounds.swLat(), bounds.swLng(), bounds.neLat(), bounds.neLng(), TOTAL_CAP);
		// 조립기는 받은 순서를 지키고 썸네일·대표 메뉴를 벌크로 채운다 — 검색 결과와 같은 줄 모양이 된다
		List<StoreSearchHit> hits = rows.stream()
			.map(row -> new StoreSearchHit(row.getStoreId(), null, null, List.of(), List.of()))
			.toList();
		return new StoresInBoundsResponse(storeSearchAssembler.assemble(hits), total);
	}

	static int clampLimit(Integer limit) {
		if (limit == null) {
			return DEFAULT_LIMIT;
		}
		return Math.min(Math.max(limit, 1), MAX_LIMIT);
	}
}
