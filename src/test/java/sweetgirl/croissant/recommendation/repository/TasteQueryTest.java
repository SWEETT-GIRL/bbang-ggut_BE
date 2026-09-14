package sweetgirl.croissant.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;

class TasteQueryTest {

	@Test
	@DisplayName("프로필이 없으면 빈 쿼리 — 축·태그·종류 전부 없음")
	void ofNull_isEmpty() {
		TasteQuery query = TasteQuery.of(null);

		assertThat(query.hasAxis()).isFalse();
		assertThat(query.tags()).isEmpty();
		assertThat(query.topKinds()).isEmpty();
		assertThat(TasteQuery.empty().hasAxis()).isFalse();
	}

	@Test
	@DisplayName("검사를 한 프로필은 4축을 double 로 옮긴다. 검사 전 프로필은 축이 없다")
	void ofProfile_axis() {
		LocalDateTime now = LocalDateTime.of(2026, 9, 14, 23, 0);
		UserTasteProfile empty = UserTasteProfile.createEmpty(7L, now);
		assertThat(TasteQuery.of(empty).hasAxis()).isFalse();

		UserTasteProfile quizzed = UserTasteProfile.createEmpty(7L, now);
		quizzed.applyQuiz(List.of(Map.of("axis", "taste", "choice", "B")),
			TasteAxis.of(new BigDecimal("0.750"), new BigDecimal("-0.250"), new BigDecimal("0.000"),
				new BigDecimal("1.000")),
			"PFLN", now);
		TasteQuery query = TasteQuery.of(quizzed);

		assertThat(query.hasAxis()).isTrue();
		assertThat(query.taste()).isEqualTo(0.75);
		assertThat(query.texture()).isEqualTo(-0.25);
		assertThat(query.filling()).isZero();
		assertThat(query.vibe()).isEqualTo(1.0);
	}
}
