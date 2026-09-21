package sweetgirl.croissant.personalization.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizAnswer;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizRequest;
import sweetgirl.croissant.personalization.dto.response.BbtiResultResponse;
import sweetgirl.croissant.personalization.dto.response.TasteLabels;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.BbtiType;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;

/**
 * 빵BTI 검사 응답을 축과 타입으로 바꿔 프로필에 쓴다 (설계 §1 폴백 · §2). 축 규칙은 {@link QuizAxisRule}.
 *
 * 검사 응답을 남긴 뒤 같은 트랜잭션에서 행동 신호를 합산한다(검사 × 3, #663 ②) — 신호가 있는 회원은 프로필과 응답 모두 합산된 축·타입이고,
 * 신호가 없으면 검사 축 그대로다. version 은 합산 결과가 지금 값과 다를 때 한 번만 오른다. 행은 배치와 같은 순서(빈 행 INSERT →
 * FOR UPDATE)로 잠가 동시 재계산과 줄을 선다. 합산이 가게 프로필 데이터 문제로 실패하면 검사 축만 저장한다 — 검사는 사용자 입력만으로
 * 성립하는 값이라 dataops 데이터 품질과 운명을 같이하지 않는다(다음 04:00 배치가 다시 합산).
 * 검사는 추천 카드의 폴백으로만 쓴다. 타입 카드는 아직 없다(09-13).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TasteQuizCommandService {

	private final UserTasteProfileRepository userTasteProfileRepository;
	private final PersonalizationProperties personalizationProperties;
	private final UserTasteProfileCommandService userTasteProfileCommandService;

	public BbtiResultResponse submit(Long memberId, BbtiQuizRequest request) {
		List<BbtiQuizAnswer> answers = request.answers();
		int answersPerAxis = QuizAxisRule.answersPerAxis(answers);
		TasteAxis quizAxis = QuizAxisRule.fromAnswers(answers, answersPerAxis);
		List<QuizChoice> choices = answers.stream().map(BbtiQuizAnswer::choice).toList();
		LocalDateTime now = LocalDateTime.now(UserTasteProfileCommandService.ZONE_ID);

		UserTasteProfile profile = userTasteProfileCommandService.lockOrCreate(memberId, now);
		profile.recordQuizAnswers(QuizAxisRule.toStoredAnswers(answers), request.quizVersion(), now);
		try {
			userTasteProfileCommandService.mergeSignals(profile, quizAxis, choices, now, now);
		} catch (RuntimeException e) {
			// 사용자 경로를 가게 프로필 데이터 품질에서 격리한다 — 검사 축으로 저장하고 다음 배치가 다시 합산(#748 리뷰)
			log.warn("[개인화] 검사 직후 신호 합산 실패 — 검사 축만 저장 memberId={}", memberId, e);
			profile.applyQuiz(QuizAxisRule.toStoredAnswers(answers), request.quizVersion(), quizAxis,
				QuizAxisRule.typeCodeOf(choices, quizAxis), now);
		}
		userTasteProfileRepository.save(profile);

		double confidence = answersPerAxis == QuizAxisRule.SHORT_QUIZ_ANSWERS_PER_AXIS
			? personalizationProperties.getShortQuizConfidence()
			: personalizationProperties.getFullQuizConfidence();
		// 라벨은 고른 갈래 그대로다 — 프로필 축과 달리 행동 신호와 섞이지 않는다. 아직 신호에서 라벨을 만들 수
		// 없기 때문이고(가게 라벨이 와야 한다), 섞을 수 있게 되기 전까지 이 값은 "이 사람이 검사에서 고른 것"이다.
		return BbtiResultResponse.of(BbtiType.fromCode(profile.getTypeCode()), profile.getAxis(),
			TasteLabels.of(choices), confidence);
	}
}
