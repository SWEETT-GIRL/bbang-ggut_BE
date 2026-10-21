package sweetgirl.croissant.searchmiss.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.naver.client.NaverLocalSearchClient;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * 네이버 지역검색 장소가 우리 DB 에 있는지 코드로 판정한다 — 강한 근거부터.
 *
 * <ol>
 *   <li>{@code naver_place_id} 일치. 가게 등록 신청이 중복을 막는 기준과 같다.</li>
 *   <li>좌표 반경 안(상태 무관) + 지점명 뗀 이름이 같거나 비슷함. {@code naver_place_id}는 enrich 배치가 나중에 채우는 값이라
 *       모든 가게에 있지 않다 — ID 가 안 맞는다고 "없다"가 아니다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class SearchMissPlaceResolver {

	// 네이버 지역검색 카테고리 예: "카페,디저트>베이커리", "음식점>카페", "제과,베이커리"
	private static final List<String> BAKERY_CATEGORY_KEYWORDS = List.of(
		"베이커리", "제과", "빵", "카페", "디저트", "도넛", "케이크", "베이글", "와플", "쿠키");

	private final NaverLocalSearchClient naverLocalSearchClient;
	private final StoreRepository storeRepository;
	private final SearchMissTriageProperties properties;

	/**
	 * @param query 네이버에 보낼 말 — 원래 검색어, 또는 블로그로 풀어낸 정식 이름
	 * @param isRelevantPlaceName 네이버가 돌려준 장소 이름이 찾던 대상과 관련 있는지. 네이버는 느슨하게 관련된 장소도 준다
	 */
	public PlaceResolution resolve(String query, Predicate<String> isRelevantPlaceName) {
		List<NaverPlaceSearchResult> relevantPlaces = naverLocalSearchClient.search(query).stream()
			.filter(place -> place.name() != null && isRelevantPlaceName.test(place.name()))
			.toList();
		if (relevantPlaces.isEmpty()) {
			return PlaceResolution.none();
		}

		Map<Long, Store> activeStores = new LinkedHashMap<>();
		Map<Long, Store> deletedStores = new LinkedHashMap<>();
		List<NaverPlaceSearchResult> conflictingPlaces = new ArrayList<>();
		List<NaverPlaceSearchResult> newStoreCandidates = new ArrayList<>();
		for (NaverPlaceSearchResult place : relevantPlaces) {
			List<Store> matched = matchStores(place);
			if (!matched.isEmpty()) {
				matched.forEach(store -> (store.getStatus() == StoreStatus.ACTIVE ? activeStores : deletedStores)
					.putIfAbsent(store.getId(), store));
				continue;
			}
			if (hasCoordinates(place) && !storeRepository.findAllWithinRadius(place.longitude(), place.latitude(),
				properties.getConflictRadiusMeters()).isEmpty()) {
				conflictingPlaces.add(place);
				continue;
			}
			if (isBakeryCategory(place.category())) {
				newStoreCandidates.add(place);
			}
		}
		return new PlaceResolution(relevantPlaces, List.copyOf(activeStores.values()),
			List.copyOf(deletedStores.values()), conflictingPlaces, newStoreCandidates);
	}

	private List<Store> matchStores(NaverPlaceSearchResult place) {
		if (place.naverPlaceId() != null) {
			Optional<Store> byPlaceId = storeRepository.findByNaverPlaceId(place.naverPlaceId());
			if (byPlaceId.isPresent()) {
				return List.of(byPlaceId.get());
			}
		}
		if (!hasCoordinates(place)) {
			return List.of();
		}
		return storeRepository.findAllWithinRadius(place.longitude(), place.latitude(),
			properties.getNearbyRadiusMeters()).stream()
			.filter(store -> SearchMissTextMatcher.isSameStoreName(store.getName(), place.name()))
			.toList();
	}

	private static boolean hasCoordinates(NaverPlaceSearchResult place) {
		return place.latitude() != null && place.longitude() != null;
	}

	static boolean isBakeryCategory(String category) {
		if (category == null) {
			return false;
		}
		String lowered = category.toLowerCase(Locale.ROOT);
		return BAKERY_CATEGORY_KEYWORDS.stream().anyMatch(lowered::contains);
	}
}
