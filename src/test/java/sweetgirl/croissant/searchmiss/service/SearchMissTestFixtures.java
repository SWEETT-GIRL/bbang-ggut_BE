package sweetgirl.croissant.searchmiss.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.mockito.quality.Strictness;

import sweetgirl.croissant.store.dto.response.NaverPlaceSearchResult;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;

final class SearchMissTestFixtures {

	static final double LATITUDE = 37.579;
	static final double LONGITUDE = 126.986;

	private SearchMissTestFixtures() {
		// 정적 팩토리
	}

	/** 판정은 가게의 id·이름·상태만 본다. 엔티티 생성자가 무거워 목으로 만든다 */
	static Store store(Long id, String name, StoreStatus status) {
		Store store = mock(Store.class, withSettings().strictness(Strictness.LENIENT));
		when(store.getId()).thenReturn(id);
		when(store.getName()).thenReturn(name);
		when(store.getStatus()).thenReturn(status);
		return store;
	}

	static NaverPlaceSearchResult place(String placeId, String name, String category) {
		return new NaverPlaceSearchResult(placeId, name, category, null, "서울 종로구", null, LATITUDE, LONGITUDE,
			"https://map.naver.com/p/entry/place/" + placeId);
	}
}
