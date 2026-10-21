package sweetgirl.croissant.personalization.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

class BbtiTypeTest {

	/** 넓히기 전의 16개 — 하나도 빠지면 안 된다 */
	private static final List<String> LEGACY_CODES = List.of(
		"SFRO", "SFRN", "SFLO", "SFLN", "SCRO", "SCRN", "SCLO", "SCLN",
		"PFRO", "PFRN", "PFLO", "PFLN", "PCRO", "PCRN", "PCLO", "PCLN");

	@Test
	@DisplayName("48타입은 4글자 코드뿐이다 — 화면 이름은 두지 않는다(#760)")
	void fortyEightCodesOnly() {
		assertThat(BbtiType.values()).hasSize(48)
			.allSatisfy(type -> assertThat(type.code()).matches("[SPTM][FCY][RL][ON]"));
	}

	@Test
	@DisplayName("자리마다 글자 집합이 겹치지 않는다 — 글자만 보고 어느 축인지 안다")
	void lettersDoNotOverlapBetweenPositions() {
		assertThat(lettersAt(0)).containsExactlyInAnyOrder('S', 'P', 'T', 'M');
		assertThat(lettersAt(1)).containsExactlyInAnyOrder('F', 'C', 'Y');
		assertThat(lettersAt(2)).containsExactlyInAnyOrder('R', 'L');
		assertThat(lettersAt(3)).containsExactlyInAnyOrder('O', 'N');

		// 네 자리의 글자를 다 모아도 중복이 없다
		assertThat(lettersAt(0).size() + lettersAt(1).size() + lettersAt(2).size() + lettersAt(3).size())
			.isEqualTo(Arrays.stream(BbtiType.values())
				.flatMap(type -> type.code().chars().mapToObj(c -> (char)c))
				.distinct().count());
	}

	@Test
	@DisplayName("옛 16타입이 그대로 남아 있다 — 넓힌 것이지 바꾼 것이 아니다")
	void legacyCodesSurvive() {
		assertThat(LEGACY_CODES).allSatisfy(code -> assertThat(BbtiType.fromCode(code).code()).isEqualTo(code));
	}

	@Test
	@DisplayName("저장된 코드로 타입을 찾고, 규칙 밖 코드는 서버 약속이 깨진 것이라 예외다")
	void fromCode() {
		assertThat(BbtiType.fromCode("SCRO")).isEqualTo(BbtiType.SCRO);
		assertThat(BbtiType.fromCode("TYLN")).isEqualTo(BbtiType.TYLN);
		assertThatThrownBy(() -> BbtiType.fromCode("XXXX"))
			.isInstanceOf(PersonalizationException.class)
			.hasFieldOrPropertyWithValue("errorCode", PersonalizationErrorCode.TASTE_AXIS_INCOMPLETE);
	}

	@Test
	@DisplayName("자리 글자를 섞어 만든 코드는 없다 — 맛 자리에 식감 글자를 넣으면 못 찾는다")
	void rejectsLettersInWrongPosition() {
		assertThatThrownBy(() -> BbtiType.fromCode("YSRO")).isInstanceOf(PersonalizationException.class);
		assertThatThrownBy(() -> BbtiType.fromCode("SFRT")).isInstanceOf(PersonalizationException.class);
	}

	private static List<Character> lettersAt(int position) {
		return Arrays.stream(BbtiType.values())
			.map(type -> type.code().charAt(position))
			.distinct()
			.toList();
	}
}
