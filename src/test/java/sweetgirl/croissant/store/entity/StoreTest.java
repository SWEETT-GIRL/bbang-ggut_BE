package sweetgirl.croissant.store.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.store.enums.StoreSource;

class StoreTest {

	private Store newStore() {
		return Store.builder()
			.name("하얀오골계 베이커리카페")
			.address("강원특별자치도 원주시 판부면 내남송길 98-11")
			.source(StoreSource.KAKAO_IMPORTED)
			.build();
	}

	@Test
	@DisplayName("진짜 무결과(noCandidates) 실패는 파킹 카운터를 누적한다")
	void markFailed_noCandidates_incrementsAttempts() {
		Store store = newStore();
		store.markNaverEnrichFailed(true);
		store.markNaverEnrichFailed(true);

		assertThat(store.getNaverEnrichAttempts()).isEqualTo(2);
		assertThat(store.getNaverMatchedAt()).isNotNull();
	}

	@Test
	@DisplayName("MISMATCH(후보 있음) 실패는 파킹 카운터를 올리지 않는다")
	void markFailed_withCandidates_doesNotIncrement() {
		Store store = newStore();
		store.markNaverEnrichFailed(false);
		store.markNaverEnrichFailed(false);

		assertThat(store.getNaverEnrichAttempts()).isZero();
		assertThat(store.getNaverMatchedAt()).isNotNull();
	}

	@Test
	@DisplayName("retry(throttle 해제)는 파킹 카운터를 0 으로 리셋한다")
	void resetThrottle_resetsAttempts() {
		Store store = newStore();
		store.markNaverEnrichFailed(true);
		store.markNaverEnrichFailed(true);
		store.markNaverEnrichFailed(true);

		store.resetNaverEnrichThrottle();

		assertThat(store.getNaverEnrichAttempts()).isZero();
		assertThat(store.getNaverMatchedAt()).isNull();
	}

	@Test
	@DisplayName("placeId 초기화(clearNaverMatch)도 파킹 카운터를 리셋한다")
	void clearNaverMatch_resetsAttempts() {
		Store store = newStore();
		store.markNaverEnrichFailed(true);

		store.clearNaverMatch();

		assertThat(store.getNaverEnrichAttempts()).isZero();
		assertThat(store.getNaverPlaceId()).isNull();
		assertThat(store.getNaverMatchedAt()).isNull();
	}
}
