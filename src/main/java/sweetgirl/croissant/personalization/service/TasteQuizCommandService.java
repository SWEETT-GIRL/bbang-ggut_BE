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
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;

/**
 * 빵BTI 검사 응답을 축과 타입으로 바꿔 프로필에 쓴다 (설계 §1 폴백 · §2).
 *
 * 축 값은 A −1 · B +1 의 축별 평균이다. 8문항에서 한 축의 두 답이 갈리면 0 이고, 타입 규칙대로 0 은 −극이다.
 * 행동 신호와의 합산(검사 × 3)은 #663 배치가 한다 — 지금은 검사만이 프로필의 축이다.
 * 검사는 추천 카드의 폴백으로만 쓴다. 타입 카드는 아직 없다(09-13).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TasteQuizCommandService {

	/** 축마다 하나면 짧은 검사(카드 폴백), 둘이면 전체 검사. 문항 수는 축 수 × 이 값이다. */
	static final int SHORT_QUIZ_ANSWERS_PER_AXIS = 1;
	static final int FULL_QUIZ_ANSWERS_PER_AXIS = 2;

	private final UserTasteProfileRepository userTasteProfileRepository;
	private final PersonalizationProperties personalizationProperties;

	public BbtiResultResponse submit(Long memberId, List<BbtiQuizAnswer> answers) {
		int answersPerAxis = answersPerAxis(answers);
		TasteAxis axis = averageAxis(answers, answersPerAxis);
		String typeCode = axis.typeCode();
		LocalDateTime now = LocalDateTime.now();

		UserTasteProfile profile = userTasteProfileRepository.findById(memberId)
			.orElseGet(() -> UserTasteProfile.createEmpty(memberId, now));
		profile.applyQuiz(toStoredAnswers(answers), axis, typeCode, now);
		userTasteProfileRepository.save(profile);

		double confidence = answersPerAxis == SHORT_QUIZ_ANSWERS_PER_AXIS
			? personalizationProperties.getShortQuizConfidence()
			: personalizationProperties.getFullQuizConfidence();
		return BbtiResultResponse.of(BbtiType.fromCode(typeCode), axis, confidence);
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

	private BigDecimal average(int poleSum, int count) {
		return BigDecimal.valueOf(poleSum).divide(BigDecimal.valueOf(count), TasteAxis.SCALE, RoundingMode.HALF_UP);
	}

	/** 저장 형태는 [{"axis": "taste", "choice": "B"}, …] — user_taste_profile.quiz_answers 의 약속. */
	private List<Map<String, String>> toStoredAnswers(List<BbtiQuizAnswer> answers) {
		return answers.stream()
			.map(answer -> Map.of("axis", answer.axis().getKey(), "choice", answer.choice().name()))
			.toList();
	}
}
