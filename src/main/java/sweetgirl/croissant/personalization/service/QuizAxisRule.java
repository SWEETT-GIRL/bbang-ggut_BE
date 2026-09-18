package sweetgirl.croissant.personalization.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizAnswer;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

/**
 * 빵BTI 검사 응답 → 검사 축의 규칙 한 곳 (설계 §1 폴백 · §2). 축 값은 A −1 · B +1 의 축별 평균이다. 8문항에서 한 축의 두 답이
 * 갈리면 0 이고, 타입 규칙대로 0 은 −극이다. 제출(TasteQuizCommandService)과 배치의 저장분 복원(UserTasteProfileCommandService)이
 * 같은 규칙을 쓴다.
 */
public final class QuizAxisRule {

	/** 축마다 하나면 짧은 검사(카드 폴백), 둘이면 전체 검사. 문항 수는 축 수 × 이 값이다. */
	public static final int SHORT_QUIZ_ANSWERS_PER_AXIS = 1;
	public static final int FULL_QUIZ_ANSWERS_PER_AXIS = 2;
	/** 저장 형태는 [{"axis": "taste", "choice": "B"}, …] — user_taste_profile.quiz_answers 의 약속. */
	private static final String STORED_AXIS = "axis";
	private static final String STORED_CHOICE = "choice";

	private QuizAxisRule() {
		// 규칙만 있는 클래스 — 인스턴스를 만들지 않는다
	}

	/**
	 * 짧은 검사면 축마다 하나, 전체 검사면 축마다 둘. 그 외는 입력 오류다.
	 *
	 * @return 축당 응답 수(1 또는 2)
	 */
	public static int answersPerAxis(List<BbtiQuizAnswer> answers) {
		int axisCount = QuizAxis.values().length;
		int expected;
		if (answers.size() == axisCount * SHORT_QUIZ_ANSWERS_PER_AXIS) {
			expected = SHORT_QUIZ_ANSWERS_PER_AXIS;
		} else if (answers.size() == axisCount * FULL_QUIZ_ANSWERS_PER_AXIS) {
			expected = FULL_QUIZ_ANSWERS_PER_AXIS;
		} else {
			throw new PersonalizationException(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID);
		}
		Map<QuizAxis, Integer> counts = new EnumMap<>(QuizAxis.class);
		answers.forEach(answer -> counts.merge(answer.axis(), 1, Integer::sum));
		for (QuizAxis quizAxis : QuizAxis.values()) {
			if (counts.getOrDefault(quizAxis, 0) != expected) {
				throw new PersonalizationException(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID);
			}
		}
		return expected;
	}

	public static TasteAxis fromAnswers(List<BbtiQuizAnswer> answers, int answersPerAxis) {
		Map<QuizAxis, Integer> poleSums = new EnumMap<>(QuizAxis.class);
		answers.forEach(answer -> poleSums.merge(answer.axis(), answer.choice().getPole(), Integer::sum));
		return TasteAxis.of(
			average(poleSums.get(QuizAxis.TASTE), answersPerAxis),
			average(poleSums.get(QuizAxis.TEXTURE), answersPerAxis),
			average(poleSums.get(QuizAxis.FILLING), answersPerAxis),
			average(poleSums.get(QuizAxis.VIBE), answersPerAxis));
	}

	/** 저장된 응답에서 검사 축을 다시 만든다 — 배치가 검사 × 가중치를 합산할 때. 네 축이 다 있어야 한다. */
	public static TasteAxis fromStoredAnswers(List<Map<String, String>> storedAnswers) {
		Map<QuizAxis, Integer> poleSums = new EnumMap<>(QuizAxis.class);
		Map<QuizAxis, Integer> counts = new EnumMap<>(QuizAxis.class);
		for (Map<String, String> stored : storedAnswers) {
			QuizAxis quizAxis = QuizAxis.fromKey(stored.get(STORED_AXIS));
			poleSums.merge(quizAxis, QuizChoice.valueOf(stored.get(STORED_CHOICE)).getPole(), Integer::sum);
			counts.merge(quizAxis, 1, Integer::sum);
		}
		for (QuizAxis quizAxis : QuizAxis.values()) {
			if (!counts.containsKey(quizAxis)) {
				throw new PersonalizationException(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID);
			}
		}
		return TasteAxis.of(
			average(poleSums.get(QuizAxis.TASTE), counts.get(QuizAxis.TASTE)),
			average(poleSums.get(QuizAxis.TEXTURE), counts.get(QuizAxis.TEXTURE)),
			average(poleSums.get(QuizAxis.FILLING), counts.get(QuizAxis.FILLING)),
			average(poleSums.get(QuizAxis.VIBE), counts.get(QuizAxis.VIBE)));
	}

	public static List<Map<String, String>> toStoredAnswers(List<BbtiQuizAnswer> answers) {
		return answers.stream()
			.map(answer -> Map.of(STORED_AXIS, answer.axis().getKey(), STORED_CHOICE, answer.choice().name()))
			.toList();
	}

	private static BigDecimal average(int poleSum, int count) {
		return BigDecimal.valueOf(poleSum).divide(BigDecimal.valueOf(count), TasteAxis.SCALE, RoundingMode.HALF_UP);
	}
}
