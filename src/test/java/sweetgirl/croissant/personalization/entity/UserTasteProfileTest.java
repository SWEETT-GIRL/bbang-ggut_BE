package sweetgirl.croissant.personalization.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;

class UserTasteProfileTest {

	private static final LocalDateTime FIRST = LocalDateTime.of(2026, 9, 7, 4, 0);
	private static final LocalDateTime LATER = LocalDateTime.of(2026, 9, 8, 4, 0);
	private static final LocalDateTime QUIZ_AT = LocalDateTime.of(2026, 9, 8, 21, 30);

	private static TasteAxis plainCrispy() {
		return TasteAxis.of(
			new BigDecimal("0.600"), new BigDecimal("0.400"), new BigDecimal("0.500"), new BigDecimal("0.200"));
	}

	@Test
	@DisplayName("빈 프로필은 축도 타입도 검사 이력도 없고 version 1 이다")
	void createEmpty() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, FIRST);

		assertThat(profile.hasAxis()).isFalse();
		assertThat(profile.hasType()).isFalse();
		assertThat(profile.hasTakenQuiz()).isFalse();
		assertThat(profile.getVersion()).isEqualTo(1);
		assertThat(profile.getTags()).isEmpty();
	}

	@Test
	@DisplayName("배치 재계산은 값을 갈아끼우고 version 을 올린다 — 추천 재사용 키가 바뀐다")
	void recompute_bumpsVersion() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, FIRST);

		profile.recompute(plainCrispy(), "PFLN", TasteTypeSource.SIGNAL, Map.of("머물기", 0.7),
			new String[] {"소금빵", "깜빠뉴"}, new String[0], TasteUserType.PLANNER, 4, 2, LATER);

		assertThat(profile.getVersion()).isEqualTo(2);
		assertThat(profile.hasAxis()).isTrue();
		assertThat(profile.getTypeCode()).isEqualTo("PFLN");
		assertThat(profile.getTypeSource()).isEqualTo(TasteTypeSource.SIGNAL);
		assertThat(profile.getTopKinds()).containsExactly("소금빵", "깜빠뉴");
		assertThat(profile.getUserType()).isEqualTo(TasteUserType.PLANNER);
		assertThat(profile.getStrongStoreCount()).isEqualTo(4);
		assertThat(profile.getComputedAt()).isEqualTo(LATER);
	}

	@Test
	@DisplayName("검사 응답을 적용하면 타입 출처가 QUIZ 가 되고 검사 시각이 남으며 version 이 오른다")
	void applyQuiz_setsQuizSourceAndAnsweredAt() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, FIRST);
		List<Map<String, String>> answers = List.of(
			Map.of("axis", "taste", "choice", "PLAIN"),
			Map.of("axis", "texture", "choice", "SOFT"),
			Map.of("axis", "filling", "choice", "SIMPLE"),
			Map.of("axis", "vibe", "choice", "TRENDY"));

		profile.applyQuiz(answers, 2, plainCrispy(), "PFLN", QUIZ_AT);

		assertThat(profile.getVersion()).isEqualTo(2);
		assertThat(profile.getTypeSource()).isEqualTo(TasteTypeSource.QUIZ);
		assertThat(profile.getQuizAnswers()).hasSize(4);
		assertThat(profile.getQuizAnsweredAt()).isEqualTo(QUIZ_AT);
		assertThat(profile.hasTakenQuiz()).isTrue();
		assertThat(profile.hasType()).isTrue();
	}

	@Test
	@DisplayName("배치 재계산은 검사 시각을 건드리지 않는다 — 타입 카드의 '저장 후 기록 N건' 기준이 유지된다")
	void recompute_keepsQuizAnsweredAt() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, FIRST);
		profile.applyQuiz(List.of(Map.of("axis", "taste", "choice", "PLAIN")), 2, plainCrispy(), "PFLN", QUIZ_AT);

		profile.recompute(plainCrispy(), "PFLN", TasteTypeSource.SIGNAL, Map.of(), null, new String[0],
			TasteUserType.BROWSER, 1, 1,
			LATER);

		assertThat(profile.getQuizAnsweredAt()).isEqualTo(QUIZ_AT);
		assertThat(profile.getComputedAt()).isEqualTo(LATER);
		assertThat(profile.getVersion()).isEqualTo(3);
	}

	@Test
	@DisplayName("검사 응답만 남기면 축·타입·version 이 그대로다 — 합산이 뒤따라 값이 바뀔 때만 version 이 오른다")
	void recordQuizAnswers_keepsAxisAndVersion() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, FIRST);

		profile.recordQuizAnswers(List.of(Map.of("axis", "taste", "choice", "PLAIN")), 2, QUIZ_AT);

		assertThat(profile.getQuizAnswers()).hasSize(1);
		assertThat(profile.getQuizAnsweredAt()).isEqualTo(QUIZ_AT);
		assertThat(profile.hasTakenQuiz()).isTrue();
		assertThat(profile.hasAxis()).isFalse();
		assertThat(profile.getVersion()).isEqualTo(1);
	}

	@Test
	@DisplayName("유형·카운트만 갱신하면 version 이 그대로다 — 추천이 읽는 값이 아니라 저장된 추천을 깨지 않는다")
	void refreshStats_keepsVersion() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(7L, FIRST);
		profile.recompute(plainCrispy(), "PFLN", TasteTypeSource.SIGNAL, Map.of(), null, new String[0],
			TasteUserType.BROWSER, 1, 0,
			FIRST);

		profile.refreshStats(TasteUserType.PLANNER, 6, 2, LATER);

		assertThat(profile.getVersion()).isEqualTo(2);
		assertThat(profile.getUserType()).isEqualTo(TasteUserType.PLANNER);
		assertThat(profile.getStrongStoreCount()).isEqualTo(6);
		assertThat(profile.getBreadSignalCount()).isEqualTo(2);
		assertThat(profile.getComputedAt()).isEqualTo(LATER);
		assertThat(profile.getTypeCode()).isEqualTo("PFLN");
	}
}
