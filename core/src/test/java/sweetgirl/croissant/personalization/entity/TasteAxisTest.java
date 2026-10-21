package sweetgirl.croissant.personalization.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

class TasteAxisTest {

	@Test
	@DisplayName("네 축이 모두 있어야 벡터로 쓸 수 있다")
	void isComplete_requiresAllFourAxes() {
		TasteAxis complete = TasteAxis.of(
			new BigDecimal("0.810"), new BigDecimal("0.900"), new BigDecimal("0.900"), new BigDecimal("0.810"));
		TasteAxis missingVibe = TasteAxis.of(
			new BigDecimal("0.810"), new BigDecimal("0.900"), new BigDecimal("0.900"), null);

		assertThat(complete.isComplete()).isTrue();
		assertThat(missingVibe.isComplete()).isFalse();
	}

	@Test
	@DisplayName("같은 값이면 같은 축이다")
	void equals_byValue() {
		TasteAxis first = TasteAxis.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
		TasteAxis second = TasteAxis.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

		assertThat(first).isEqualTo(second);
	}

	@Test
	@DisplayName("타입 코드는 네 축의 부호 조합이다 — 전부 양수면 PCLN, 전부 음수면 SFRO")
	void typeCode_fromSigns() {
		TasteAxis allPositive = TasteAxis.of(
			new BigDecimal("0.600"), new BigDecimal("0.400"), new BigDecimal("0.500"), new BigDecimal("0.200"));
		TasteAxis allNegative = TasteAxis.of(
			new BigDecimal("-0.600"), new BigDecimal("-0.400"), new BigDecimal("-0.500"), new BigDecimal("-0.200"));

		assertThat(allPositive.typeCode()).isEqualTo("PCLN");
		assertThat(allNegative.typeCode()).isEqualTo("SFRO");
	}

	@Test
	@DisplayName("0 에 가까워도 양수면 +1 극이다 — 접으면 애매한 대상이 전부 SFRO 로 몰린다")
	void typeCode_doesNotFoldWeakAxesToNegative() {
		TasteAxis barelyPositive = TasteAxis.of(
			new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("0.001"));

		assertThat(barelyPositive.typeCode()).isEqualTo("PCLN");
		assertThat(barelyPositive.isWeak(barelyPositive.getTaste())).isTrue();
	}

	@Test
	@DisplayName("0 은 −1 극으로 센다 — 부호가 양수일 때만 +1 극이다")
	void typeCode_treatsZeroAsNegativePole() {
		TasteAxis zeros = TasteAxis.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

		assertThat(zeros.typeCode()).isEqualTo("SFRO");
	}

	@Test
	@DisplayName("자리마다 제 축의 글자가 선다 — 맛 P, 식감 C, 속 L, 신상 N")
	void typeCode_eachPositionFollowsItsAxis() {
		BigDecimal on = new BigDecimal("0.500");
		BigDecimal off = new BigDecimal("-0.500");

		assertThat(TasteAxis.of(on, off, off, off).typeCode()).isEqualTo("PFRO");
		assertThat(TasteAxis.of(off, on, off, off).typeCode()).isEqualTo("SCRO");
		assertThat(TasteAxis.of(off, off, on, off).typeCode()).isEqualTo("SFLO");
		assertThat(TasteAxis.of(off, off, off, on).typeCode()).isEqualTo("SFRN");
	}

	@Test
	@DisplayName("축이 다 있지 않으면 타입을 정하지 않는다 — 근거가 없으면 타입도 없다")
	void typeCode_requiresCompleteAxis() {
		TasteAxis partial = TasteAxis.of(new BigDecimal("0.500"), null, new BigDecimal("0.500"), null);

		assertThatThrownBy(partial::typeCode)
			.isInstanceOfSatisfying(PersonalizationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(PersonalizationErrorCode.TASTE_AXIS_INCOMPLETE));
	}
}
