package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.dto.request.BbtiQuizAnswer;
import sweetgirl.croissant.personalization.dto.response.BbtiResultResponse;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.enums.TasteProfileRefreshOutcome;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;

@ExtendWith(MockitoExtension.class)
class TasteQuizCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private UserTasteProfileRepository userTasteProfileRepository;
	@Mock
	private UserTasteProfileCommandService userTasteProfileCommandService;
	@Spy
	private PersonalizationProperties personalizationProperties = new PersonalizationProperties();

	@InjectMocks
	private TasteQuizCommandService tasteQuizCommandService;

	private static BbtiQuizAnswer answer(QuizAxis axis, QuizChoice choice) {
		return new BbtiQuizAnswer(axis, choice);
	}

	private static List<BbtiQuizAnswer> shortQuiz(QuizChoice taste, QuizChoice texture, QuizChoice filling,
			QuizChoice vibe) {
		return List.of(answer(QuizAxis.TASTE, taste), answer(QuizAxis.TEXTURE, texture),
			answer(QuizAxis.FILLING, filling), answer(QuizAxis.VIBE, vibe));
	}

	private UserTasteProfile lockedEmptyProfile() {
		UserTasteProfile profile = UserTasteProfile.createEmpty(MEMBER_ID, LocalDateTime.of(2026, 9, 18, 21, 0));
		given(userTasteProfileCommandService.lockOrCreate(eq(MEMBER_ID), any())).willReturn(profile);
		return profile;
	}

	/** 신호가 없는 회원의 합산 = 검사 축 그대로 — 진짜 서비스가 하는 일을 목이 흉내 낸다 */
	private void mergeAppliesQuizAxis(UserTasteProfile profile) {
		given(userTasteProfileCommandService.mergeSignals(eq(profile), any(TasteAxis.class), any(), any()))
			.willAnswer(call -> {
				TasteAxis quizAxis = call.getArgument(1);
				profile.recompute(quizAxis, quizAxis.typeCode(), TasteTypeSource.QUIZ, Map.of(), null, new String[0],
					null, 0, 0,
					call.getArgument(3));
				return TasteProfileRefreshOutcome.RECOMPUTED;
			});
	}

	@Test
	@DisplayName("4문항: B 만 고르면 담백·바삭·심플·신상 PCLN 이고 프로필이 새로 생겨 저장된다")
	void submit_shortQuiz_createsProfile() {
		mergeAppliesQuizAxis(lockedEmptyProfile());
		given(userTasteProfileRepository.save(any(UserTasteProfile.class))).willAnswer(call -> call.getArgument(0));

		BbtiResultResponse result = tasteQuizCommandService.submit(MEMBER_ID,
			shortQuiz(QuizChoice.B, QuizChoice.B, QuizChoice.B, QuizChoice.B));

		assertThat(result.typeCode()).isEqualTo("PCLN");
		assertThat(result.axis()).allSatisfy(value -> assertThat(value).isEqualByComparingTo("1.000"));
		assertThat(result.confidence()).isEqualTo(personalizationProperties.getShortQuizConfidence());

		ArgumentCaptor<UserTasteProfile> saved = ArgumentCaptor.forClass(UserTasteProfile.class);
		verify(userTasteProfileRepository).save(saved.capture());
		assertThat(saved.getValue().getTypeCode()).isEqualTo("PCLN");
		assertThat(saved.getValue().getTypeSource()).isEqualTo(TasteTypeSource.QUIZ);
		assertThat(saved.getValue().getQuizAnsweredAt()).isNotNull();
		assertThat(saved.getValue().getQuizAnswers()).hasSize(4)
			.first()
			.satisfies(stored -> assertThat(stored).containsEntry("axis", "taste").containsEntry("choice", "B"));
		assertThat(saved.getValue().getVersion()).isEqualTo(2); // 빈 행 1 → 합산 한 번
		then(userTasteProfileCommandService).should().mergeSignals(eq(saved.getValue()), any(TasteAxis.class), any(),
			any());
	}

	@Test
	@DisplayName("8문항: 축마다 두 답을 평균한다 — 맛이 A·B 로 갈리면 0 이고 0 은 −극(S)이다")
	void submit_fullQuiz_averagesEachAxis() {
		mergeAppliesQuizAxis(lockedEmptyProfile());
		given(userTasteProfileRepository.save(any(UserTasteProfile.class))).willAnswer(call -> call.getArgument(0));
		List<BbtiQuizAnswer> answers = List.of(
			answer(QuizAxis.TASTE, QuizChoice.A), answer(QuizAxis.TASTE, QuizChoice.B),
			answer(QuizAxis.TEXTURE, QuizChoice.B), answer(QuizAxis.TEXTURE, QuizChoice.B),
			answer(QuizAxis.FILLING, QuizChoice.A), answer(QuizAxis.FILLING, QuizChoice.A),
			answer(QuizAxis.VIBE, QuizChoice.B), answer(QuizAxis.VIBE, QuizChoice.A));

		BbtiResultResponse result = tasteQuizCommandService.submit(MEMBER_ID, answers);

		assertThat(result.typeCode()).isEqualTo("SCRO");
		assertThat(result.axis().get(0)).isEqualByComparingTo("0.000");
		assertThat(result.axis().get(1)).isEqualByComparingTo("1.000");
		assertThat(result.axis().get(2)).isEqualByComparingTo("-1.000");
		assertThat(result.axis().get(3)).isEqualByComparingTo("0.000");
		assertThat(result.confidence()).isEqualTo(personalizationProperties.getFullQuizConfidence());
	}

	@Test
	@DisplayName("프로필이 이미 있으면 그 행에 덮어쓰고 버전이 오른다 — 옛 추천은 재사용되지 않는다")
	void submit_existingProfile_isUpdatedInPlace() {
		UserTasteProfile existing = UserTasteProfile.createEmpty(MEMBER_ID, LocalDateTime.of(2026, 9, 1, 4, 0));
		int versionBefore = existing.getVersion();
		given(userTasteProfileCommandService.lockOrCreate(eq(MEMBER_ID), any())).willReturn(existing);
		mergeAppliesQuizAxis(existing);
		given(userTasteProfileRepository.save(existing)).willReturn(existing);

		tasteQuizCommandService.submit(MEMBER_ID, shortQuiz(QuizChoice.A, QuizChoice.A, QuizChoice.A, QuizChoice.A));

		assertThat(existing.getTypeCode()).isEqualTo("SFRO");
		assertThat(existing.getVersion()).isEqualTo(versionBefore + 1);
		verify(userTasteProfileRepository).save(existing);
	}

	@Test
	@DisplayName("신호가 있는 회원은 검사 직후 합산된 축·타입이 프로필에도 응답에도 실린다 — 추천 카드 제목과 같은 값")
	void submit_withSignals_respondsMergedProfile() {
		UserTasteProfile profile = lockedEmptyProfile();
		given(userTasteProfileRepository.save(profile)).willReturn(profile);
		BigDecimal half = new BigDecimal("0.500");
		given(userTasteProfileCommandService.mergeSignals(eq(profile), any(TasteAxis.class), any(), any()))
			.willAnswer(call -> {
				profile.recompute(TasteAxis.of(half, half, half.negate(), half), "PCRN", TasteTypeSource.SIGNAL,
					Map.of(), null, new String[0], null, 3, 0, LocalDateTime.of(2026, 9, 18, 21, 0));
				return TasteProfileRefreshOutcome.RECOMPUTED;
			});

		BbtiResultResponse result = tasteQuizCommandService.submit(MEMBER_ID,
			shortQuiz(QuizChoice.B, QuizChoice.B, QuizChoice.B, QuizChoice.B));

		assertThat(result.typeCode()).isEqualTo("PCRN");
		assertThat(result.axis().get(2)).isEqualByComparingTo("-0.500");
		assertThat(profile.getQuizAnsweredAt()).isNotNull();
	}

	@Test
	@DisplayName("합산이 가게 프로필 데이터 문제로 실패해도 검사는 저장된다 — 검사 축·타입으로 응답, 다음 배치가 다시 합산")
	void submit_mergeFails_fallsBackToQuizAxis() {
		UserTasteProfile profile = lockedEmptyProfile();
		given(userTasteProfileCommandService.mergeSignals(eq(profile), any(TasteAxis.class), any(), any()))
			.willThrow(new IllegalStateException("store_profile.tags 가 JSON 객체가 아니다"));
		given(userTasteProfileRepository.save(profile)).willReturn(profile);

		BbtiResultResponse result = tasteQuizCommandService.submit(MEMBER_ID,
			shortQuiz(QuizChoice.B, QuizChoice.B, QuizChoice.B, QuizChoice.B));

		assertThat(result.typeCode()).isEqualTo("PCLN");
		assertThat(profile.getTypeSource()).isEqualTo(TasteTypeSource.QUIZ);
		assertThat(profile.getQuizAnswers()).hasSize(4);
		assertThat(profile.getVersion()).isEqualTo(2);
		verify(userTasteProfileRepository).save(profile);
	}

	@Test
	@DisplayName("4·8개가 아니거나 축이 빠지거나 겹치면 입력 오류다")
	void submit_rejectsWrongShape() {
		List<BbtiQuizAnswer> fiveAnswers = List.of(
			answer(QuizAxis.TASTE, QuizChoice.A), answer(QuizAxis.TEXTURE, QuizChoice.A),
			answer(QuizAxis.FILLING, QuizChoice.A), answer(QuizAxis.VIBE, QuizChoice.A),
			answer(QuizAxis.VIBE, QuizChoice.B));
		List<BbtiQuizAnswer> duplicatedAxis = List.of(
			answer(QuizAxis.TASTE, QuizChoice.A), answer(QuizAxis.TASTE, QuizChoice.B),
			answer(QuizAxis.FILLING, QuizChoice.A), answer(QuizAxis.VIBE, QuizChoice.A));

		assertThatThrownBy(() -> tasteQuizCommandService.submit(MEMBER_ID, fiveAnswers))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID));
		assertThatThrownBy(() -> tasteQuizCommandService.submit(MEMBER_ID, duplicatedAxis))
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.QUIZ_ANSWERS_INVALID));
	}
}
