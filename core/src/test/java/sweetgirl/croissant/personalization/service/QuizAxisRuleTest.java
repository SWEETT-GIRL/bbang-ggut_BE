package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
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
			answer(QuizAxis.TASTE, QuizChoice.SWEET), answer(QuizAxis.TASTE, QuizChoice.PLAIN),
			answer(QuizAxis.TEXTURE, QuizChoice.CRISP), answer(QuizAxis.TEXTURE, QuizChoice.CRISP),
			answer(QuizAxis.FILLING, QuizChoice.FULL), answer(QuizAxis.FILLING, QuizChoice.FULL),
			answer(QuizAxis.VIBE, QuizChoice.TRENDY), answer(QuizAxis.VIBE, QuizChoice.CLASSIC));

		TasteAxis submitted = QuizAxisRule.fromAnswers(answers, QuizAxisRule.answersPerAxis(answers));
		TasteAxis restored = QuizAxisRule.fromStoredAnswers(QuizAxisRule.toStoredAnswers(answers));

		assertThat(submitted.getTaste()).isEqualByComparingTo("0.000");
		assertThat(submitted.getTexture()).isEqualByComparingTo("1.000");
		assertThat(submitted.getFilling()).isEqualByComparingTo("-1.000");
		assertThat(submitted.getVibe()).isEqualByComparingTo("0.000");
		assertThat(restored).isEqualTo(submitted);
	}

	@Test
	@DisplayName("짭짤은 요약 축에서 담백과 같은 +1 이고, 풍미·쫄깃은 어느 극도 아니라 0 이다")
	void newBranchesCollapseInTheSummaryAxis() {
		TasteAxis salty = axisOf(QuizChoice.SALTY, QuizChoice.CHEWY, QuizChoice.SIMPLE, QuizChoice.TRENDY);
		TasteAxis plain = axisOf(QuizChoice.PLAIN, QuizChoice.CHEWY, QuizChoice.SIMPLE, QuizChoice.TRENDY);
		TasteAxis aroma = axisOf(QuizChoice.AROMA, QuizChoice.CHEWY, QuizChoice.SIMPLE, QuizChoice.TRENDY);

		// 축 하나로는 짭짤과 담백이 갈리지 않는다 — 그래서 라벨을 따로 둔다
		assertThat(salty.getTaste()).isEqualByComparingTo("1.000");
		assertThat(plain.getTaste()).isEqualByComparingTo("1.000");
		assertThat(aroma.getTaste()).isEqualByComparingTo("0.000");
		assertThat(salty.getTexture()).isEqualByComparingTo("0.000");
	}

	@Test
	@DisplayName("고른 갈래가 타입 코드의 맛·식감 글자를 정한다 — 속·스타일은 축 부호 그대로")
	void typeCodeComesFromChosenBranches() {
		assertThat(typeCodeOf(QuizChoice.SALTY, QuizChoice.CHEWY, QuizChoice.SIMPLE, QuizChoice.TRENDY))
			.isEqualTo("TYLN");
		assertThat(typeCodeOf(QuizChoice.AROMA, QuizChoice.SOFT, QuizChoice.FULL, QuizChoice.CLASSIC))
			.isEqualTo("MFRO");
		// 옛 16타입 안에 드는 조합은 예전과 같은 코드가 나온다
		assertThat(typeCodeOf(QuizChoice.SWEET, QuizChoice.CRISP, QuizChoice.FULL, QuizChoice.CLASSIC))
			.isEqualTo("SCRO");
	}

	@Test
	@DisplayName("한 축의 두 답이 갈리면 정해진 우선순위로 글자를 하나 고른다")
	void splitAnswersFallBackToPriority() {
		List<BbtiQuizAnswer> answers = List.of(
			answer(QuizAxis.TASTE, QuizChoice.AROMA), answer(QuizAxis.TASTE, QuizChoice.SWEET),
			answer(QuizAxis.TEXTURE, QuizChoice.SOFT), answer(QuizAxis.TEXTURE, QuizChoice.CHEWY),
			answer(QuizAxis.FILLING, QuizChoice.SIMPLE), answer(QuizAxis.FILLING, QuizChoice.SIMPLE),
			answer(QuizAxis.VIBE, QuizChoice.TRENDY), answer(QuizAxis.VIBE, QuizChoice.TRENDY));
		TasteAxis axis = QuizAxisRule.fromAnswers(answers, QuizAxisRule.answersPerAxis(answers));

		// 맛은 달콤 > 짭짤 > 담백 > 풍미, 식감은 바삭 > 쫄깃 > 부드 순 — dataops 가 가게 코드에 쓰는 규칙과 같다
		assertThat(QuizAxisRule.typeCodeOf(choicesOf(answers), axis)).isEqualTo("SYLN");
	}

	@Test
	@DisplayName("선택지가 그 문항의 축과 맞지 않으면 400 — 맛 문항에 식감 갈래가 오면 막는다")
	void rejectsChoiceFromAnotherAxis() {
		List<BbtiQuizAnswer> answers = List.of(
			answer(QuizAxis.TASTE, QuizChoice.CHEWY),
			answer(QuizAxis.TEXTURE, QuizChoice.CRISP),
			answer(QuizAxis.FILLING, QuizChoice.FULL),
			answer(QuizAxis.VIBE, QuizChoice.CLASSIC));

		assertThatThrownBy(() -> QuizAxisRule.answersPerAxis(answers))
			.isInstanceOf(PersonalizationException.class)
			.hasFieldOrPropertyWithValue("errorCode", PersonalizationErrorCode.QUIZ_CHOICE_AXIS_MISMATCH);
	}

	@Test
	@DisplayName("축이 빠진 저장 응답은 축을 만들 수 없다")
	void fromStored_rejectsMissingAxis() {
		assertThatThrownBy(() -> QuizAxisRule.fromStoredAnswers(List.of(Map.of("axis", "taste", "choice", "SWEET"))))
			.isInstanceOf(PersonalizationException.class);
	}

	@Test
	@DisplayName("지금 규칙에 없는 선택지 이름은 400 — 배치가 회원 하나 때문에 멈추지 않는다")
	void rejectsUnknownStoredChoice() {
		// 옛 판(v1)의 자리 문자 — 지금 규칙에 없으므로 거부되어야 한다. 유효한 이름으로 바꾸면 이 테스트는 뜻을 잃는다
		List<Map<String, String>> stored = List.of(Map.of("axis", "taste", "choice", "A"));

		assertThatThrownBy(() -> QuizAxisRule.choicesOf(stored))
			.isInstanceOf(PersonalizationException.class)
			.hasFieldOrPropertyWithValue("errorCode", PersonalizationErrorCode.QUIZ_ANSWERS_INVALID);
	}

	private static BbtiQuizAnswer answer(QuizAxis axis, QuizChoice choice) {
		return new BbtiQuizAnswer(axis, choice);
	}

	/** 4문항(축마다 하나)의 축 */
	private static TasteAxis axisOf(QuizChoice taste, QuizChoice texture, QuizChoice filling, QuizChoice vibe) {
		List<BbtiQuizAnswer> answers = shortQuiz(taste, texture, filling, vibe);
		return QuizAxisRule.fromAnswers(answers, QuizAxisRule.answersPerAxis(answers));
	}

	private static String typeCodeOf(QuizChoice taste, QuizChoice texture, QuizChoice filling, QuizChoice vibe) {
		List<BbtiQuizAnswer> answers = shortQuiz(taste, texture, filling, vibe);
		return QuizAxisRule.typeCodeOf(choicesOf(answers), axisOf(taste, texture, filling, vibe));
	}

	private static List<BbtiQuizAnswer> shortQuiz(QuizChoice taste, QuizChoice texture, QuizChoice filling,
			QuizChoice vibe) {
		return List.of(answer(QuizAxis.TASTE, taste), answer(QuizAxis.TEXTURE, texture),
			answer(QuizAxis.FILLING, filling), answer(QuizAxis.VIBE, vibe));
	}

	private static List<QuizChoice> choicesOf(List<BbtiQuizAnswer> answers) {
		return answers.stream().map(BbtiQuizAnswer::choice).toList();
	}
}
