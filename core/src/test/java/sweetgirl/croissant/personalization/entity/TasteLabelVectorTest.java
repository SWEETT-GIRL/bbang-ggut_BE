package sweetgirl.croissant.personalization.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TasteLabelVectorTest {

	private static TasteLabelVector full() {
		return TasteLabelVector.of(label("1"), label("0"), label("0.5"), label("0"), label("0"), label("1"), label("0"),
			label("0.25"), label("0.75"));
	}

	@Test
	@DisplayName("values() 는 정해진 순서로 아홉 값을 준다 — 회원과 가게가 이 순서로 맞댄다")
	void valuesKeepOrder() {
		assertThat(full().values())
			.hasSize(TasteLabelVector.DIMENSION)
			.containsExactly(1, 0, 0.5, 0, 0, 1, 0, 0.25, 0.75);
	}

	@Test
	@DisplayName("아홉이 다 있어야 벡터다 — 하나라도 비면 코사인이 반쪽이 된다")
	void isCompleteNeedsAllNine() {
		assertThat(full().isComplete()).isTrue();

		TasteLabelVector missingOne = TasteLabelVector.of(label("1"), label("0"), label("0.5"), label("0"),
			label("0"), label("1"), label("0"), label("0.25"), null);
		assertThat(missingOne.isComplete()).isFalse();

		TasteLabelVector empty = TasteLabelVector.of(null, null, null, null, null, null, null, null, null);
		assertThat(empty.isComplete()).isFalse();
	}

	@Test
	@DisplayName("빈 자리는 0 으로 읽힌다 — 벡터로 쓰기 전에 isComplete 로 걸러야 하는 이유다")
	void missingValuesReadAsZero() {
		TasteLabelVector partial = TasteLabelVector.of(label("1"), null, null, null, null, null, null, null, null);

		// 0 과 "없음" 이 값에서 구분되지 않는다. 그래서 불완전한 벡터는 아예 쓰지 않는다
		assertThat(partial.values()).containsExactly(1, 0, 0, 0, 0, 0, 0, 0, 0);
		assertThat(partial.isComplete()).isFalse();
	}

	@Test
	@DisplayName("꽉 참·트렌디가 1 이다 — axis_filling(꽉 참 −1)과 부호가 반대인 자리")
	void fillingAndVibeAreDegreesNotPoles() {
		TasteLabelVector rich = TasteLabelVector.of(label("0"), label("0"), label("0"), label("0"),
			label("0"), label("0"), label("0"), label("1"), label("1"));
		TasteLabelVector simpleClassic = TasteLabelVector.of(label("0"), label("0"), label("0"), label("0"),
			label("0"), label("0"), label("0"), label("0"), label("0"));

		assertThat(rich.getFillingRich()).isEqualByComparingTo("1");
		assertThat(rich.getVibeTrendy()).isEqualByComparingTo("1");
		assertThat(simpleClassic.getFillingRich()).isEqualByComparingTo("0");
		assertThat(simpleClassic.getVibeTrendy()).isEqualByComparingTo("0");
	}

	private static BigDecimal label(String value) {
		return new BigDecimal(value);
	}
}
