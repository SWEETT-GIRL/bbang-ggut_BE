package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.dto.response.NextStoreResponse;
import sweetgirl.croissant.breadlog.dto.response.NextStoreResponse.Reason;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.store.dto.projection.NextStoreCandidateProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("NextStoreQueryService")
class NextStoreQueryServiceTest {

	private static final Long MEMBER = 7L;
	private static final GeometryFactory GEOMETRY = new GeometryFactory();

	@Mock
	private SavedStoreRepository savedStoreRepository;
	@Mock
	private StoreRepository storeRepository;

	@InjectMocks
	private NextStoreQueryService nextStoreQueryService;

	private Store store(Long id, double latitude, double longitude) {
		Store store = Store.builder().name("가게" + id).address("서울").build();
		ReflectionTestUtils.setField(store, "id", id);
		ReflectionTestUtils.setField(store, "location", GEOMETRY.createPoint(new Coordinate(longitude, latitude)));
		return store;
	}

	private NextStoreCandidateProjection candidate(Long id, double distance) {
		NextStoreCandidateProjection candidate = mock(NextStoreCandidateProjection.class);
		lenient().when(candidate.getId()).thenReturn(id);
		lenient().when(candidate.getDistanceMeters()).thenReturn(distance);
		return candidate;
	}

	@Test
	@DisplayName("담아 둔 가게를 먼저 싣고(거리 계산), 모자라면 근처 후보로 채우되 겹치는 가게는 건너뛴다")
	void savedFirstThenNearby() {
		given(savedStoreRepository.findUnrecordedActiveStores(MEMBER, 10L, PageRequest.of(0, 3)))
			.willReturn(List.of(store(20L, 37.5, 127.0)));
		List<NextStoreCandidateProjection> nearby = List.of(
			candidate(20L, 0), candidate(21L, 300.4), candidate(22L, 900.0), candidate(23L, 1500.0));
		given(storeRepository.findNearbyUnrecordedPopular(eq(MEMBER), eq(10L), eq(127.0), eq(37.5), anyDouble(), eq(4)))
			.willReturn(nearby);

		List<NextStoreResponse> result = nextStoreQueryService.nextStores(MEMBER, 10L, 37.5, 127.0);

		assertThat(result).extracting(NextStoreResponse::storeId).containsExactly(20L, 21L, 22L);
		assertThat(result.get(0).reason()).isEqualTo(Reason.SAVED);
		assertThat(result.get(0).distanceMeters()).isZero();
		assertThat(result.get(1).reason()).isEqualTo(Reason.NEARBY);
		assertThat(result.get(1).distanceMeters()).isEqualTo(300);
	}

	@Test
	@DisplayName("좌표가 없으면 담아 둔 가게만 — 거리는 비고 근처 쿼리는 부르지 않는다. 뺄 가게가 없으면 -1")
	void withoutCoordinates() {
		given(savedStoreRepository.findUnrecordedActiveStores(MEMBER, -1L, PageRequest.of(0, 3)))
			.willReturn(List.of(store(20L, 37.5, 127.0)));

		List<NextStoreResponse> result = nextStoreQueryService.nextStores(MEMBER, null, null, null);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).distanceMeters()).isNull();
		then(storeRepository).should(org.mockito.Mockito.never())
			.findNearbyUnrecordedPopular(anyLong(), anyLong(), anyDouble(), anyDouble(), anyDouble(), anyInt());
	}

	@Test
	@DisplayName("로그인 · 게스트 세션이 없으면 MEMBER401")
	void unauthorized() {
		assertThatThrownBy(() -> nextStoreQueryService.nextStores(null, 10L, 37.5, 127.0))
			.isInstanceOf(MemberException.class);
		then(savedStoreRepository).should(org.mockito.Mockito.never()).findUnrecordedActiveStores(any(), any(), any());
	}
}
