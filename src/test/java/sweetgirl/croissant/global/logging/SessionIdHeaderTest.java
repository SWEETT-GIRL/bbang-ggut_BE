package sweetgirl.croissant.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionIdHeaderTest {

	@Test
	@DisplayName("세션 id 헤더는 앞뒤 공백을 자르고, 비었거나 ASCII 가 아니거나 64자를 넘으면 null — 숫자 문자열·UUID 모두 통과")
	void normalize() {
		assertThat(SessionIdHeader.NAME).isEqualTo("X-Session-Id");
		assertThat(SessionIdHeader.normalize(null)).isNull();
		assertThat(SessionIdHeader.normalize("   ")).isNull();
		assertThat(SessionIdHeader.normalize(" 1757894400123 ")).isEqualTo("1757894400123");
		assertThat(SessionIdHeader.normalize("35505610-349e-4826-881b-24d918123270"))
			.isEqualTo("35505610-349e-4826-881b-24d918123270");
		assertThat(SessionIdHeader.normalize("세션")).isNull();
		assertThat(SessionIdHeader.normalize("a".repeat(65))).isNull();
		assertThat(SessionIdHeader.normalize("a".repeat(64))).hasSize(64);
	}
}
