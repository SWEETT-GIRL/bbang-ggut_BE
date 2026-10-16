package sweetgirl.croissant.global.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServerTimeHeaderFilterTest {

	@Test
	@DisplayName("응답에 서버 시각을 epoch ms 로 싣고 체인을 잇는다")
	void addsEpochMillisHeader() throws Exception {
		Instant now = Instant.parse("2026-10-16T05:00:00.123Z");
		ServerTimeHeaderFilter filter = new ServerTimeHeaderFilter(Clock.fixed(now, ZoneOffset.UTC));
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(new MockHttpServletRequest("GET", "/stores/1"), response, chain);

		assertThat(response.getHeader(ServerTimeHeaderFilter.HEADER)).isEqualTo(String.valueOf(now.toEpochMilli()));
		assertThat(chain.getRequest()).isNotNull();
	}
}
