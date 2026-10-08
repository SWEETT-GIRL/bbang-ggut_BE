package sweetgirl.croissant.letter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LetterReplyChoicesTest {

	@Test
	@DisplayName("숫자만 보내든 말을 붙이든 같은 번호다")
	void readsNumberWithAnyWording() {
		assertThat(LetterReplyChoices.parse("2", 4)).containsExactly(2);
		assertThat(LetterReplyChoices.parse("2번입니다!", 4)).containsExactly(2);
		assertThat(LetterReplyChoices.parse("② 요", 4)).containsExactly(2);
	}

	@Test
	@DisplayName("여러 개 고르면 전부, 같은 번호는 한 번")
	void readsSeveralOnce() {
		assertThat(LetterReplyChoices.parse("2, 3번이요 그리고 2", 4)).containsExactly(2, 3);
	}

	@Test
	@DisplayName("보기 밖의 숫자와 다른 숫자에 붙은 숫자는 번호가 아니다")
	void ignoresOutOfRangeAndEmbeddedDigits() {
		assertThat(LetterReplyChoices.parse("5번", 4)).isEmpty();
		assertThat(LetterReplyChoices.parse("2026년 10/30에 먹었어요", 4)).isEmpty();
	}

	@Test
	@DisplayName("숫자가 없으면 번호 없음")
	void noNumber() {
		assertThat(LetterReplyChoices.parse("빵을 너무 많이 먹어서요", 4)).isEmpty();
		assertThat(LetterReplyChoices.parse(null, 4)).isEmpty();
	}
}
