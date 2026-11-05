package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.exception.CourseException;

@DisplayName("MemoDraftRateLimiter")
class MemoDraftRateLimiterTest {

	private FakeTicker ticker;
	private MemoDraftRateLimiter rateLimiter;

	@BeforeEach
	void setUp() {
		CourseDraftProperties properties = new CourseDraftProperties();
		properties.setRatePerSecond(3);
		ticker = new FakeTicker();
		rateLimiter = new MemoDraftRateLimiter(properties, ticker);
	}

	@Test
	@DisplayName("1초 안에 한도까지는 통과하고 한도를 넘는 요청은 429")
	void overLimitWithinSecond_rejected() {
		rateLimiter.check(1L);
		rateLimiter.check(1L);
		rateLimiter.check(1L);

		assertThatThrownBy(() -> rateLimiter.check(1L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_DRAFT_TOO_FREQUENT.getMessage());
	}

	@Test
	@DisplayName("1초가 지나면 창이 새로 시작된다")
	void afterOneSecond_windowResets() {
		rateLimiter.check(1L);
		rateLimiter.check(1L);
		rateLimiter.check(1L);
		ticker.advance(Duration.ofMillis(1_001));

		assertThatCode(() -> rateLimiter.check(1L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("회원별로 센다 — 한 사람이 한도를 넘어도 다른 사람은 막히지 않는다")
	void countedPerMember() {
		rateLimiter.check(1L);
		rateLimiter.check(1L);
		rateLimiter.check(1L);

		assertThatCode(() -> rateLimiter.check(2L)).doesNotThrowAnyException();
	}
}
