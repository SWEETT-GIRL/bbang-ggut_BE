package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

	@Test
	@DisplayName("8 자이고 헷갈리는 글자(0 · O · 1 · I)가 없다")
	void generates() {
		InviteCodeGenerator generator = new InviteCodeGenerator(new RewardRandom());

		for (int attempt = 0; attempt < 200; attempt++) {
			assertThat(generator.generate()).hasSize(8).matches("[A-HJ-NP-Z2-9]{8}");
		}
	}
}
