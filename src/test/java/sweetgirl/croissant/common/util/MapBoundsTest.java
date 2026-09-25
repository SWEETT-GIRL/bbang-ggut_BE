package sweetgirl.croissant.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MapBoundsTest {

	@Test
	@DisplayName("가운데는 두 모서리의 중점이다")
	void center_isMidpoint() {
		MapBounds bounds = new MapBounds(37.5, 126.9, 37.6, 127.1);

		assertThat(bounds.centerLat()).isEqualTo(37.55, within(1e-9));
		assertThat(bounds.centerLng()).isEqualTo(127.0, within(1e-9));
	}

	@Test
	@DisplayName("남서가 북동보다 크면 400 — 뒤집힌 영역이나 날짜변경선 넘는 영역은 받지 않는다")
	void rejectsInvertedCorners() {
		assertThatThrownBy(() -> new MapBounds(37.6, 126.9, 37.5, 127.1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MapBounds(37.5, 127.1, 37.6, 126.9))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("WGS84 범위 밖 좌표는 400 — PostGIS 가 500 을 내기 전에 거른다")
	void rejectsOutOfRange() {
		assertThatThrownBy(() -> new MapBounds(-91, 126.9, 37.6, 127.1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MapBounds(37.5, 126.9, 37.6, 181))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("네 값이 다 없으면 영역 없음, 다 있으면 영역")
	void ofNullable_allOrNothing() {
		assertThat(MapBounds.ofNullable(null, null, null, null)).isEmpty();
		assertThat(MapBounds.ofNullable(37.5, 126.9, 37.6, 127.1))
			.contains(new MapBounds(37.5, 126.9, 37.6, 127.1));
	}

	@Test
	@DisplayName("일부만 오면 400 — 조용히 영역 없이 찾으면 앱은 거른 줄 알고 전국 결과를 받는다")
	void ofNullable_rejectsPartial() {
		assertThatThrownBy(() -> MapBounds.ofNullable(37.5, 126.9, null, 127.1))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
