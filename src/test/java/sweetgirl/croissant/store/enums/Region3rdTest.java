package sweetgirl.croissant.store.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class Region3rdTest {

	@Nested
	@DisplayName("of — 시도까지 받아 겹치는 구를 가려낸다")
	class Of {

		@Test
		@DisplayName("이름이 겹치는 구는 시도에 따라 다른 상수로 간다")
		void ambiguousGuResolvesBySido() {
			assertThat(Region3rd.of("서울", "중구", "명동")).isEqualTo(Region3rd.서울_중구_명동);
			assertThat(Region3rd.of("부산", "중구", "광복동")).isEqualTo(Region3rd.부산_중구_광복동);
			assertThat(Region3rd.of("서울", "중구", "전체")).isEqualTo(Region3rd.서울_중구_전체);
			assertThat(Region3rd.of("부산", "중구", "전체")).isEqualTo(Region3rd.부산_중구_전체);
		}

		@Test
		@DisplayName("겹치지 않는 구는 시도를 붙이지 않은 상수로 간다")
		void uniqueGuKeepsShortForm() {
			assertThat(Region3rd.of("서울", "강남구", "역삼동")).isEqualTo(Region3rd.강남구_역삼동);
			assertThat(Region3rd.of("서울", "마포구", "전체")).isEqualTo(Region3rd.마포구_전체);
		}

		@Test
		@DisplayName("시도를 몰라도 겹치지 않는 구는 찾을 수 있다")
		void uniqueGuResolvesWithoutSido() {
			assertThat(Region3rd.of(null, "강남구", "역삼동")).isEqualTo(Region3rd.강남구_역삼동);
		}

		@Test
		@DisplayName("없는 지역은 null")
		void unknownReturnsNull() {
			assertThat(Region3rd.of("서울", "없는구", "없는동")).isNull();
			assertThat(Region3rd.of("서울", null, "역삼동")).isNull();
		}
	}

	@Nested
	@DisplayName("fromRequest — 구버전 앱이 보내는 옛 이름도 받는다")
	class FromRequest {

		@Test
		@DisplayName("새 형식은 그대로 해석한다")
		void newFormat() {
			assertThat(Region3rd.fromRequest("부산_중구_광복동")).isEqualTo(Region3rd.부산_중구_광복동);
			assertThat(Region3rd.fromRequest("강남구_역삼동")).isEqualTo(Region3rd.강남구_역삼동);
		}

		@Test
		@DisplayName("시도 없는 옛 이름은 가게가 가장 많은 시도로 보낸다")
		void legacyFormatFallsBackToBusiestSido() {
			assertThat(Region3rd.fromRequest("중구_전체")).isEqualTo(Region3rd.서울_중구_전체);
			assertThat(Region3rd.fromRequest("동구_전체")).isEqualTo(Region3rd.대구_동구_전체);
			assertThat(Region3rd.fromRequest("고성군_전체")).isEqualTo(Region3rd.강원_고성군_전체);
		}

		@Test
		@DisplayName("옛 동 이름은 그 동이 실제로 있는 시도로 간다")
		void legacyDongResolvesToItsOwnSido() {
			// 광복동은 부산 중구에만 있다 — 대표 시도(서울)가 아니라 부산으로 가야 한다
			assertThat(Region3rd.fromRequest("중구_광복동")).isEqualTo(Region3rd.부산_중구_광복동);
		}

		@Test
		@DisplayName("모르는 값과 null은 null")
		void unknownReturnsNull() {
			assertThat(Region3rd.fromRequest("없는_지역")).isNull();
			assertThat(Region3rd.fromRequest(null)).isNull();
		}
	}

	@Test
	@DisplayName("leafName — 형식이 둘이어도 항상 마지막 조각")
	void leafName() {
		assertThat(Region3rd.부산_중구_광복동.leafName()).isEqualTo("광복동");
		assertThat(Region3rd.강남구_역삼동.leafName()).isEqualTo("역삼동");
		assertThat(Region3rd.서울_중구_전체.leafName()).isEqualTo("전체");
	}
}
