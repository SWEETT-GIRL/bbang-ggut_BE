package sweetgirl.croissant.personalization.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizAnswer;
import sweetgirl.croissant.personalization.dto.response.BbtiResultResponse;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.BbtiType;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;

/**
 * 빵BTI 검사 응답을 축과 타입으로 바꿔 프로필에 쓴다 (설계 §1 폴백 · §2).
 *
 * 축 값은 A −1 · B +1 의 축별 평균이다. 8문항에서 한 축의 두 답이 갈리면 0 이고, 타입 규칙대로 0 은 −극이다.
 * 검사 축을 쓴 뒤 같은 트랜잭션에서 행동 신호를 합산한다(검사 × 3, #663 ②) — 신호가 있는 회원은 프로필과 응답 모두 합산된 축·타입이고,
 * 신호가 없으면 검사 축 그대로다. 행은 배치와 같은 순서(빈 행 INSERT → FOR UPDATE)로 잠가 동시 재계산과 줄을 선다.
 * 검사는 추천 카드의 폴백으로만 쓴다. 타입 카드는 아직 없다(09-13).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TasteQuizCommandService {

	/** 축마다 하나면 짧은 검사(카드 폴백), 둘이면 전체 검사. 문항 수는 축 수 × 이 값이다. */
	static final int SHORT_QUIZ_ANSWERS_PER_AXIS = 1;
	static final int FULL_QUIZ_ANSWERS_PER_AXIS = 2;
	private static final String STORED_AXIS = "axis";
	private static final String STORED_CHOICE = "choice";

	private final UserTasteProfileRepository userTasteProfileRepository;
	private final PersonalizationProperties personalizationProperties;
	private final UserTasteProfileCommandService userTasteProfileCommandService;

	public BbtiResultResponse submit(Long memberId, List<BbtiQuizAnswer> answers) {
		int answersPerAxis = answersPerAxis(answers);
		TasteAxis quizAxis = averageAxis(answers, answersPerAxis);
		String quizTypeCode = quizAxis.typeCode();
		LocalDateTime now = LocalDateTime.now();

		UserTasteProfile profile = userTasteProfileCommandService.lockOrCreate(memberId, now);
		profile.applyQuiz(toStoredAnswers(answers), quizAxis, quizTypeCode, now);
		userTasteProfileCommandService.mergeSignals(profile, quizAxis, now);
		userTasteProfileRepository.save(profile);

		double confidence = answersPerAxis == SHORT_QUIZ_ANSWERS_PER_AXIS
			? personalizationProperties.getShortQuizConfidence()
			: personalizationProperties.getFullQuizConfidence();
		return BbtiResultResponse.of(BbtiType.fromCode(profile.getTypeCode()), profile.getAxis(), confidence);
	}

	/**
	 * 저장된 응답([{"axis": "taste", "choice": "B"}, …])에서 검사 축을 다시 만든다 — 배치가 검사 × 3 을 합산할 때. 제출 때와 같은
	 * 규칙(축별 평균)이다.
	 */
	public static TasteAxis axisOfStored(List<Map<String, String>> storedAnswers) {
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

	/** 짧은 검사면 축마다 하나, 전체 검사면 축마다 둘. 그 외는 입력 오류다. */
	private int answersPerAxis(List<BbtiQuizAnswer> answers) {
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

	private TasteAxis averageAxis(List<BbtiQuizAnswer> answers, int answersPerAxis) {
		Map<QuizAxis, Integer> poleSums = new EnumMap<>(QuizAxis.class);
		answers.forEach(answer -> poleSums.merge(answer.axis(), answer.choice().getPole(), Integer::sum));
		return TasteAxis.of(
			average(poleSums.get(QuizAxis.TASTE), answersPerAxis),
			average(poleSums.get(QuizAxis.TEXTURE), answersPerAxis),
			average(poleSums.get(QuizAxis.FILLING), answersPerAxis),
			average(poleSums.get(QuizAxis.VIBE), answersPerAxis));
	}

	private static BigDecimal average(int poleSum, int count) {
		return BigDecimal.valueOf(poleSum).divide(BigDecimal.valueOf(count), TasteAxis.SCALE, RoundingMode.HALF_UP);
	}

	/** 저장 형태는 [{"axis": "taste", "choice": "B"}, …] — user_taste_profile.quiz_answers 의 약속. */
	private List<Map<String, String>> toStoredAnswers(List<BbtiQuizAnswer> answers) {
		return answers.stream()
			.map(answer -> Map.of(STORED_AXIS, answer.axis().getKey(), STORED_CHOICE, answer.choice().name()))
			.toList();
	}
}
