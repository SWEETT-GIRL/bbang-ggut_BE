package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TowerBreadPickerTest {

	private final TowerBreadPicker picker = new TowerBreadPicker();

	@Test
	@DisplayName("사용자가 고른 빵이 있으면 그대로 쓴다")
	void keepsChosenBread() {
		assertThat(picker.resolve(3L, "bagel")).isEqualTo("bagel");
	}

	@Test
	@DisplayName("고른 빵이 없으면 기록 id 로 고른다 — 같은 기록은 늘 같은 빵이다")
	void picksSameBreadForSameRecord() {
		String first = picker.resolve(77L, null);
		String second = picker.resolve(77L, null);

		assertThat(first).isEqualTo(second);
		assertThat(first).isNotNull();
	}

	@Test
	@DisplayName("앱이 아는 다섯 종류 안에서만 고른다")
	void picksOnlyKnownKinds() {
		for (long recordId = 1; recordId <= 50; recordId++) {
			assertThat(picker.resolve(recordId, null))
				.isIn("croissant", "melonpan", "soboro", "bagel", "anpan");
		}
	}

	@Test
	@DisplayName("앱과 같은 규칙으로 고른다 — 서버가 내려주기 전후로 그림이 안 바뀐다")
	void matchesTheAppsRule() {
		// 앱: BREAD_KINDS[Math.abs(recordId) % BREAD_KINDS.length]
		assertThat(picker.resolve(0L, null)).isEqualTo("croissant");
		assertThat(picker.resolve(1L, null)).isEqualTo("melonpan");
		assertThat(picker.resolve(4L, null)).isEqualTo("anpan");
		assertThat(picker.resolve(5L, null)).isEqualTo("croissant");
	}

	@Test
	@DisplayName("빈 문자열은 안 고른 것으로 본다")
	void treatsBlankAsUnchosen() {
		assertThat(picker.resolve(1L, "  ")).isEqualTo("melonpan");
	}

	@Test
	@DisplayName("아직 저장되지 않은 기록이면 비운다")
	void returnsNullWithoutRecordId() {
		assertThat(picker.resolve(null, null)).isNull();
	}
}
