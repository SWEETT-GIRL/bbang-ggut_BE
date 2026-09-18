package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.dto.request.BbtiQuizAnswer;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

class QuizAxisRuleTest {

	@Test
	@DisplayName("제출 응답과 저장 응답에서 같은 축이 나온다 — 8문항은 축별 평균, 갈리면 0")
	void fromAnswers_andFromStored_agree() {
		List<BbtiQuizAnswer> answers = List.of(
			new BbtiQuizAnswer(QuizAxis.TASTE, QuizChoice.A), new BbtiQuizAnswer(QuizAxis.TASTE, QuizChoice.B),
			new BbtiQuizAnswer(QuizAxis.TEXTURE, QuizChoice.B), new BbtiQuizAnswer(QuizAxis.TEXTURE, QuizChoice.B),
			new BbtiQuizAnswer(QuizAxis.FILLING, QuizChoice.A), new BbtiQuizAnswer(QuizAxis.FILLING, QuizChoice.A),
			new BbtiQuizAnswer(QuizAxis.VIBE, QuizChoice.B), new BbtiQuizAnswer(QuizAxis.VIBE, QuizChoice.A));

		TasteAxis submitted = QuizAxisRule.fromAnswers(answers, QuizAxisRule.answersPerAxis(answers));
		TasteAxis restored = QuizAxisRule.fromStoredAnswers(QuizAxisRule.toStoredAnswers(answers));

		assertThat(submitted.getTaste()).isEqualByComparingTo("0.000");
		assertThat(submitted.getTexture()).isEqualByComparingTo("1.000");
		assertThat(submitted.getFilling()).isEqualByComparingTo("-1.000");
		assertThat(submitted.getVibe()).isEqualByComparingTo("0.000");
		assertThat(restored).isEqualTo(submitted);
	}

	@Test
	@DisplayName("축이 빠진 저장 응답은 축을 만들 수 없다")
	void fromStored_rejectsMissingAxis() {
		assertThatThrownBy(() -> QuizAxisRule.fromStoredAnswers(List.of(Map.of("axis", "taste", "choice", "A"))))
			.isInstanceOf(PersonalizationException.class);
	}
}
