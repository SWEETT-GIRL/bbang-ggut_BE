package sweetgirl.croissant.member.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NicknameNormalizerTest {

	@Test
	@DisplayName("NFD 한글 · 대문자 · 공백은 같은 이름이다")
	void normalizes() {
		String nfd = Normalizer.normalize("여린 바타르", Normalizer.Form.NFD);

		assertThat(NicknameNormalizer.normalize(nfd)).isEqualTo("여린바타르");
		assertThat(NicknameNormalizer.normalize("Bread Lover")).isEqualTo("breadlover");
		assertThat(NicknameNormalizer.normalize(" 빵 순 이 ")).isEqualTo("빵순이");
		assertThat(NicknameNormalizer.normalize(null)).isEmpty();
	}
}
