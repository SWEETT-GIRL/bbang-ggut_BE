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
	/** 저장 형태는 [{"axis": "taste", "choice": "SALTY"}, …] — user_taste_profile.quiz_answers 의 약속. */
	private static final String STORED_AXIS = "axis";
	private static final String STORED_CHOICE = "choice";

	/**
	 * 한 축의 두 답이 갈렸을 때 코드 글자로 삼을 순서. dataops 가 가게 코드에 쓰는 동점 규칙과 같다 —
	 * 빈도가 낮은 갈래를 남기면 코드가 희귀해져 뜻이 흐려지고, 회원과 가게가 다른 규칙을 쓰면 나중에 코드를
	 * 맞댈 수 없다.
	 */
	private static final List<QuizChoice> TASTE_PRIORITY = List.of(QuizChoice.SWEET, QuizChoice.SALTY, QuizChoice.PLAIN,
		QuizChoice.AROMA);
	private static final List<QuizChoice> TEXTURE_PRIORITY = List.of(QuizChoice.CRISP, QuizChoice.CHEWY,
		QuizChoice.SOFT);

	/** 타입 코드에서 속·스타일이 앉는 자리 — 이 두 자리는 축 부호가 곧 글자라 축 코드에서 그대로 떼어 온다. */
	private static final int FILLING_POSITION = 2;
	private static final int VIBE_POSITION = 3;

	private QuizAxisRule() {
		// 규칙만 있는 클래스 — 인스턴스를 만들지 않는다
	}

	/**
	 * 짧은 검사면 축마다 하나, 전체 검사면 축마다 둘. 그 외는 입력 오류다.
	 *
	 * <p>선택지가 그 문항의 축에 속하는지도 같이 본다 — 맛 문항에 {@code CHEWY} 가 오면 막는다. 선택지가
	 * 자리 문자(A/B)가 아니라 갈래 이름이 되면서 생긴 검사다. 이걸 빼면 맛 문항의 답이 식감 라벨로 저장되고,
	 * 아무 데서도 오류가 나지 않아 조용히 틀린 취향이 남는다.
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
		for (BbtiQuizAnswer answer : answers) {
			if (!answer.choice().belongsTo(answer.axis())) {
				throw new PersonalizationException(PersonalizationErrorCode.QUIZ_CHOICE_AXIS_MISMATCH);
			}
			counts.merge(answer.axis(), 1, Integer::sum);
		}
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

	/**
	 * 저장된 응답에서 고른 선택지를 다시 읽는다 — 라벨과 타입 코드가 모두 여기서 나온다.
	 *
	 * @throws PersonalizationException {@code QUIZ_ANSWERS_INVALID} — 선택지 이름이 지금 규칙에 없을 때.
	 *         배치가 회원 하나 때문에 통째로 멈추지 않도록 도메인 예외로 바꿔 던진다.
	 */
	public static List<QuizChoice> choicesOf(List<Map<String, String>> storedAnswers) {
		return storedAnswers.stream().map(stored -> choiceOf(stored.get(STORED_CHOICE))).toList();
	}

	private static QuizChoice choiceOf(String stored) {
		try {
			return QuizChoice.valueOf(stored);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new PersonalizationException(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID);
		}
	}

	/** 저장된 응답에서 검사 축을 다시 만든다 — 배치가 검사 × 가중치를 합산할 때. 네 축이 다 있어야 한다. */
	public static TasteAxis fromStoredAnswers(List<Map<String, String>> storedAnswers) {
		Map<QuizAxis, Integer> poleSums = new EnumMap<>(QuizAxis.class);
		Map<QuizAxis, Integer> counts = new EnumMap<>(QuizAxis.class);
		for (Map<String, String> stored : storedAnswers) {
			QuizAxis quizAxis = QuizAxis.fromKey(stored.get(STORED_AXIS));
			poleSums.merge(quizAxis, choiceOf(stored.get(STORED_CHOICE)).getPole(), Integer::sum);
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

	/**
	 * 고른 갈래로 48타입 코드를 만든다 — 맛·식감 두 자리는 고른 갈래가 그대로 글자가 되고, 속·스타일 두 자리는
	 * 갈래가 둘뿐이라 축 부호가 곧 글자다({@link TasteAxis#typeCode()} 와 같은 규칙).
	 *
	 * <p><b>8문항에서 한 축의 두 답이 갈리면</b> 아래 우선순위로 하나를 고른다. 순서는 dataops 가 가게 코드에
	 * 쓰기로 한 동점 규칙과 같다 — 빈도가 낮은 갈래를 남기면 코드가 희귀해져 뜻이 흐려지기 때문이다. 회원과
	 * 가게가 같은 규칙을 써야 나중에 코드를 맞댈 수 있다.
	 *
	 * <p>라벨({@code TasteLabels})은 이렇게 하나로 줄이지 않는다 — 거기에는 두 갈래가 모두 1 로 남는다.
	 * 코드는 한 글자여야 해서 줄이는 것이지, "둘 다 좋아한다" 가 틀린 게 아니다.
	 */
	public static String typeCodeOf(List<QuizChoice> choices, TasteAxis axis) {
		String axisCode = axis.typeCode();
		return new StringBuilder(4)
			.append(letterOf(choices, TASTE_PRIORITY))
			.append(letterOf(choices, TEXTURE_PRIORITY))
			.append(axisCode.charAt(FILLING_POSITION))
			.append(axisCode.charAt(VIBE_POSITION))
			.toString();
	}

	/**
	 * 우선순위 목록에서 회원이 고른 것 중 가장 앞선 갈래의 글자.
	 *
	 * @throws PersonalizationException {@code QUIZ_ANSWERS_INVALID} — 그 축의 답이 하나도 없을 때.
	 *         {@link #answersPerAxis}가 이미 막으므로 여기 오면 서버 버그다.
	 */
	private static char letterOf(List<QuizChoice> choices, List<QuizChoice> priority) {
		return priority.stream()
			.filter(choices::contains)
			.findFirst()
			.orElseThrow(() -> new PersonalizationException(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID))
			.getTypeLetter();
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
