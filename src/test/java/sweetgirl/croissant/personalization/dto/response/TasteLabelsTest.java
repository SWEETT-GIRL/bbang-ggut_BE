package sweetgirl.croissant.personalization.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.enums.QuizAxis;
import sweetgirl.croissant.personalization.enums.QuizChoice;

class TasteLabelsTest {

	@Test
	@DisplayName("고른 갈래만 1 이고 나머지는 0 이다")
	void chosenBranchesAreOne() {
		TasteLabels labels = TasteLabels.of(List.of(QuizChoice.SALTY, QuizChoice.CHEWY, QuizChoice.SIMPLE,
			QuizChoice.TRENDY));

		assertThat(labels.salty()).isEqualTo(1);
		assertThat(labels.chewy()).isEqualTo(1);
		assertThat(labels.sweet()).isZero();
		assertThat(labels.plain()).isZero();
		assertThat(labels.aroma()).isZero();
		assertThat(labels.soft()).isZero();
		assertThat(labels.crisp()).isZero();
	}

	@Test
	@DisplayName("속·스타일은 두 갈래가 키 하나를 나눠 쓴다 — 반대쪽을 고르면 0 이다")
	void fillingAndVibeAreDegrees() {
		TasteLabels simpleTrendy = TasteLabels.of(List.of(QuizChoice.SIMPLE, QuizChoice.TRENDY));
		TasteLabels fullClassic = TasteLabels.of(List.of(QuizChoice.FULL, QuizChoice.CLASSIC));

		assertThat(simpleTrendy.filling()).isEqualTo(1);
		assertThat(simpleTrendy.vibe()).isEqualTo(1);
		assertThat(fullClassic.filling()).isZero();
		assertThat(fullClassic.vibe()).isZero();
	}

	@Test
	@DisplayName("8문항에서 한 축의 두 답이 갈리면 둘 다 1 이다 — 평균 내 0 으로 만들지 않는다")
	void splitAnswersKeepBothLabels() {
		TasteLabels labels = TasteLabels.of(List.of(QuizChoice.SWEET, QuizChoice.SALTY));

		// 축 요약값은 이 경우 0 이 되지만(서로 상쇄), 라벨은 "둘 다 좋아한다" 를 그대로 남긴다
		assertThat(labels.sweet()).isEqualTo(1);
		assertThat(labels.salty()).isEqualTo(1);
		assertThat(labels.plain()).isZero();
	}

	@Test
	@DisplayName("모든 선택지의 라벨 키가 응답 필드에 있다 — 갈래를 더하고 필드를 잊으면 값이 조용히 사라진다")
	void everyChoiceHasAField() {
		List<String> fields = Arrays.stream(TasteLabels.class.getRecordComponents())
			.map(component -> component.getName())
			.toList();

		assertThat(Arrays.stream(QuizChoice.values()).map(QuizChoice::getLabel).distinct())
			.allSatisfy(label -> assertThat(fields).contains(label));
		assertThat(fields).hasSize(9);
	}

	@Test
	@DisplayName("선택지는 저마다 한 축에만 속한다 — 축마다 갈래 수가 4·3·2·2 다")
	void eachChoiceBelongsToExactlyOneAxis() {
		assertThat(countOf(QuizAxis.TASTE)).isEqualTo(4);
		assertThat(countOf(QuizAxis.TEXTURE)).isEqualTo(3);
		assertThat(countOf(QuizAxis.FILLING)).isEqualTo(2);
		assertThat(countOf(QuizAxis.VIBE)).isEqualTo(2);

		for (QuizChoice choice : QuizChoice.values()) {
			assertThat(Arrays.stream(QuizAxis.values()).filter(choice::belongsTo).count()).isEqualTo(1);
		}
	}

	private static long countOf(QuizAxis axis) {
		return Arrays.stream(QuizChoice.values()).filter(choice -> choice.belongsTo(axis)).count();
	}
}
