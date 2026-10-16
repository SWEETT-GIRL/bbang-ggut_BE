package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.LATITUDE;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.LONGITUDE;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.place;
import static sweetgirl.croissant.searchmiss.service.SearchMissTestFixtures.store;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.naver.client.NaverLocalSearchClient;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class SearchMissPlaceResolverTest {

	@Mock
	private NaverLocalSearchClient naverLocalSearchClient;

	@Mock
	private StoreRepository storeRepository;

	private SearchMissTriageProperties properties;
	private SearchMissPlaceResolver resolver;

	@BeforeEach
	void setUp() {
		properties = new SearchMissTriageProperties();
		resolver = new SearchMissPlaceResolver(naverLocalSearchClient, storeRepository, properties);
	}

	@Test
	@DisplayName("naver_place_id 가 일치하면 좌표를 보지 않고 같은 가게로 확정한다")
	void resolve_byPlaceId() {
		Store store = store(1L, "런던베이글뮤지엄 안국점", StoreStatus.ACTIVE);
		given(naverLocalSearchClient.search("런던베이글뮤지엄"))
			.willReturn(List.of(place("100", "런던베이글뮤지엄 안국점", "카페,디저트>베이커리")));
		given(storeRepository.findByNaverPlaceId("100")).willReturn(Optional.of(store));

		PlaceResolution resolution = resolver.resolve("런던베이글뮤지엄", name -> true);

		assertThat(resolution.activeStores()).containsExactly(store);
		verify(storeRepository, never()).findAllWithinRadius(anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	@DisplayName("ID 가 없으면 반경 안에서 지점명 뗀 이름이 같은 가게를 찾는다 — 삭제된 가게도 잡는다")
	void resolve_byCoordinatesIncludingDeleted() {
		Store deleted = store(2L, "런던베이글뮤지엄", StoreStatus.DELETED);
		Store neighbor = store(3L, "어니언 안국", StoreStatus.ACTIVE);
		given(naverLocalSearchClient.search("런던베이글뮤지엄"))
			.willReturn(List.of(place("100", "런던베이글뮤지엄 안국점", "베이커리")));
		given(storeRepository.findByNaverPlaceId("100")).willReturn(Optional.empty());
		given(storeRepository.findAllWithinRadius(LONGITUDE, LATITUDE, 50)).willReturn(List.of(deleted, neighbor));

		PlaceResolution resolution = resolver.resolve("런던베이글뮤지엄", name -> true);

		assertThat(resolution.activeStores()).isEmpty();
		assertThat(resolution.deletedStores()).containsExactly(deleted);
	}

	@Test
	@DisplayName("근처에 같은 이름이 없고 빵집 카테고리면 신규 가게 후보다")
	void resolve_newStoreCandidate() {
		given(naverLocalSearchClient.search("새빵집"))
			.willReturn(List.of(place("200", "새빵집", "음식점>제과,베이커리")));
		given(storeRepository.findByNaverPlaceId("200")).willReturn(Optional.empty());
		given(storeRepository.findAllWithinRadius(eq(LONGITUDE), eq(LATITUDE), anyDouble())).willReturn(List.of());

		PlaceResolution resolution = resolver.resolve("새빵집", name -> true);

		assertThat(resolution.newStoreCandidates()).extracting("naverPlaceId").containsExactly("200");
	}

	@Test
	@DisplayName("바로 옆에 이름이 다른 우리 가게가 있으면 신규 후보가 아니라 사람이 볼 장소다")
	void resolve_conflictingPlace() {
		Store other = store(4L, "옛날빵집", StoreStatus.ACTIVE);
		given(naverLocalSearchClient.search("새빵집"))
			.willReturn(List.of(place("200", "새빵집", "베이커리")));
		given(storeRepository.findByNaverPlaceId("200")).willReturn(Optional.empty());
		given(storeRepository.findAllWithinRadius(LONGITUDE, LATITUDE, 50)).willReturn(List.of(other));
		given(storeRepository.findAllWithinRadius(LONGITUDE, LATITUDE, 10)).willReturn(List.of(other));

		PlaceResolution resolution = resolver.resolve("새빵집", name -> true);

		assertThat(resolution.conflictingPlaces()).hasSize(1);
		assertThat(resolution.newStoreCandidates()).isEmpty();
	}

	@Test
	@DisplayName("빵집·카페가 아닌 장소는 신규 후보로 올리지 않는다")
	void resolve_nonBakeryPlace() {
		given(naverLocalSearchClient.search("성수"))
			.willReturn(List.of(place("300", "성수 주민센터", "공공기관")));
		given(storeRepository.findByNaverPlaceId("300")).willReturn(Optional.empty());
		given(storeRepository.findAllWithinRadius(eq(LONGITUDE), eq(LATITUDE), anyDouble())).willReturn(List.of());

		PlaceResolution resolution = resolver.resolve("성수", name -> true);

		assertThat(resolution.hasNoRelevantPlace()).isFalse();
		assertThat(resolution.newStoreCandidates()).isEmpty();
	}

	@Test
	@DisplayName("찾던 대상과 관련 없는 장소는 버린다 — 네이버는 느슨하게 관련된 장소도 준다")
	void resolve_filtersIrrelevantPlaces() {
		given(naverLocalSearchClient.search("런베뮤"))
			.willReturn(List.of(place("400", "베이글하우스", "베이커리")));

		PlaceResolution resolution = resolver.resolve("런베뮤", name -> name.startsWith("런던"));

		assertThat(resolution.hasNoRelevantPlace()).isTrue();
		verify(storeRepository, never()).findByNaverPlaceId("400");
	}

	@Test
	@DisplayName("네이버 카테고리 문자열에 빵집·카페 단어가 있으면 빵집 카테고리다")
	void isBakeryCategory() {
		assertThat(SearchMissPlaceResolver.isBakeryCategory("카페,디저트>베이커리")).isTrue();
		assertThat(SearchMissPlaceResolver.isBakeryCategory("한식>국밥")).isFalse();
		assertThat(SearchMissPlaceResolver.isBakeryCategory(null)).isFalse();
	}
}
