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

	@Test
	@DisplayName("공유 카운터가 있으면 그 값으로 판정한다 — 다른 인스턴스가 쓴 횟수도 한도에 든다")
	void sharedCounter_decides() {
		CourseDraftProperties properties = new CourseDraftProperties();
		properties.setRatePerSecond(3);
		java.util.concurrent.atomic.AtomicLong shared = new java.util.concurrent.atomic.AtomicLong(2); // 다른 인스턴스가 이미 2회
		MemoDraftRateLimiter limiter = new MemoDraftRateLimiter(properties, ticker,
			memberId -> shared.incrementAndGet());

		assertThatCode(() -> limiter.check(1L)).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.check(1L)).isInstanceOf(CourseException.class);
	}

	@Test
	@DisplayName("공유 카운터(Redis)가 실패하면 인스턴스 카운터로 물러나 계속 제한한다")
	void sharedCounterFails_fallsBackToLocal() {
		CourseDraftProperties properties = new CourseDraftProperties();
		properties.setRatePerSecond(3);
		MemoDraftRateLimiter limiter = new MemoDraftRateLimiter(properties, ticker, memberId -> {
			throw new org.springframework.dao.QueryTimeoutException("redis down");
		});

		limiter.check(1L);
		limiter.check(1L);
		limiter.check(1L);
		assertThatThrownBy(() -> limiter.check(1L)).isInstanceOf(CourseException.class);
	}
}
