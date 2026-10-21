package sweetgirl.croissant.searchmiss.service;

import java.util.List;

import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;
import sweetgirl.croissant.store.entity.Store;

/**
 * 네이버 지역검색 장소를 우리 가게와 대조한 결과.
 *
 * @param relevantPlaces 검색어와 관련 있다고 본 네이버 장소 전부
 * @param activeStores 같은 가게로 확인된 우리 가게(ACTIVE)
 * @param deletedStores 같은 가게로 확인됐지만 삭제된 우리 가게
 * @param conflictingPlaces 바로 옆에 이름이 다른 우리 가게가 있는 장소 — 가게가 바뀌었을 수 있어 사람이 본다
 * @param newStoreCandidates 우리 DB 에 없는 빵집·카페
 */
public record PlaceResolution(
		List<NaverPlaceSearchResult> relevantPlaces,
		List<Store> activeStores,
		List<Store> deletedStores,
		List<NaverPlaceSearchResult> conflictingPlaces,
		List<NaverPlaceSearchResult> newStoreCandidates) {

	public static PlaceResolution none() {
		return new PlaceResolution(List.of(), List.of(), List.of(), List.of(), List.of());
	}

	public boolean hasNoRelevantPlace() {
		return relevantPlaces.isEmpty();
	}
}
