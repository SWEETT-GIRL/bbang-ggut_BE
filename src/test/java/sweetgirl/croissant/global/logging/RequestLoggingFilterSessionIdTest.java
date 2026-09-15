package sweetgirl.croissant.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.global.logging.filter.RequestLoggingFilter;

class RequestLoggingFilterSessionIdTest {

	@Test
	@DisplayName("세션 id 헤더는 앞뒤 공백을 자르고, 비었거나 ASCII 가 아니거나 64자를 넘으면 null")
	void normalizeSessionId() {
		assertThat(RequestLoggingFilter.normalizeSessionId(null)).isNull();
		assertThat(RequestLoggingFilter.normalizeSessionId("   ")).isNull();
		assertThat(RequestLoggingFilter.normalizeSessionId(" 1726000000123 ")).isEqualTo("1726000000123");
		assertThat(RequestLoggingFilter.normalizeSessionId("3f2b9c1e-8d7a-4c11-9b3e-1a2b3c4d5e6f"))
			.isEqualTo("3f2b9c1e-8d7a-4c11-9b3e-1a2b3c4d5e6f");
		assertThat(RequestLoggingFilter.normalizeSessionId("세션")).isNull();
		assertThat(RequestLoggingFilter.normalizeSessionId("a".repeat(65))).isNull();
		assertThat(RequestLoggingFilter.normalizeSessionId("a".repeat(64))).hasSize(64);
	}
}
