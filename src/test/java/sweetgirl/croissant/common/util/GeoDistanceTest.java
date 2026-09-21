package sweetgirl.croissant.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeoDistanceTest {

	@Test
	@DisplayName("같은 점은 0m, 위도 1도는 약 111.2km, 성수동 안 두 빵집은 수백 m 다")
	void metersBetween() {
		assertThat(GeoDistance.metersBetween(37.5446, 127.0557, 37.5446, 127.0557)).isZero();
		assertThat(GeoDistance.metersBetween(37.0, 127.0, 38.0, 127.0)).isBetween(111_000, 111_400);
		// 성수역 부근 ↔ 서울숲 부근
		assertThat(GeoDistance.metersBetween(37.5446, 127.0557, 37.5444, 127.0374)).isBetween(1_500, 1_700);
	}

	@Test
	@DisplayName("거리는 방향에 관계없이 같다")
	void symmetric() {
		int forward = GeoDistance.metersBetween(37.5446, 127.0557, 37.5510, 127.0440);
		int backward = GeoDistance.metersBetween(37.5510, 127.0440, 37.5446, 127.0557);

		assertThat(forward).isEqualTo(backward).isGreaterThan(0);
	}
}
