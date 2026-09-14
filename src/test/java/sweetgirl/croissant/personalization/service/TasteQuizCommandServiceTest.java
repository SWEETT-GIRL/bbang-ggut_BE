package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;

@ExtendWith(MockitoExtension.class)
class TasteQuizCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private UserTasteProfileRepository userTasteProfileRepository;
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

	@Test
	@DisplayName("4문항: B 만 고르면 담백·바삭·심플·신상 PCLN 이고 프로필이 새로 생겨 저장된다")
	void submit_shortQuiz_createsProfile() {
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(userTasteProfileRepository.save(any(UserTasteProfile.class))).willAnswer(call -> call.getArgument(0));

		BbtiResultResponse result = tasteQuizCommandService.submit(MEMBER_ID,
			shortQuiz(QuizChoice.B, QuizChoice.B, QuizChoice.B, QuizChoice.B));

		assertThat(result.typeCode()).isEqualTo("PCLN");
		assertThat(result.typeLabel()).isEqualTo("뉴웨이브 미니멀");
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
	}

	@Test
	@DisplayName("8문항: 축마다 두 답을 평균한다 — 맛이 A·B 로 갈리면 0 이고 0 은 −극(S)이다")
	void submit_fullQuiz_averagesEachAxis() {
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
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
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(existing));
		given(userTasteProfileRepository.save(existing)).willReturn(existing);

		tasteQuizCommandService.submit(MEMBER_ID, shortQuiz(QuizChoice.A, QuizChoice.A, QuizChoice.A, QuizChoice.A));

		assertThat(existing.getTypeCode()).isEqualTo("SFRO");
		assertThat(existing.getVersion()).isEqualTo(versionBefore + 1);
		verify(userTasteProfileRepository).save(existing);
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
