package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.exception.CourseException;

/**
 * 회원당 초당 PUT memo-draft 횟수 제한. 앱은 0.5초 디바운스라 정상이면 초당 2회를 넘지 않는다.
 *
 * <p>기존 {@code RateLimitFilter} 는 IP 기준·1분 창·인증 전 필터라 회원을 모른다 — 통신사 NAT 뒤 여러 명이 한도를 나눠 쓰게 된다.
 * 인스턴스 메모리라 서버 2대면 한도가 2배다(이중화 때 Redis 로 옮길 항목).
 */
@Component
@ConditionalOnProperty(name = CourseDraftConfig.REDIS_MODE_PROPERTY)
public class MemoDraftRateLimiter {

	private final int ratePerSecond;
	private final Cache<Long, AtomicInteger> counters;

	public MemoDraftRateLimiter(CourseDraftProperties properties) {
		this(properties, Ticker.systemTicker());
	}

	MemoDraftRateLimiter(CourseDraftProperties properties, Ticker ticker) {
		this.ratePerSecond = properties.getRatePerSecond();
		this.counters = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(1))
			.maximumSize(100_000)
			.ticker(ticker)
			.build();
	}

	/** 1초 창(첫 요청 기준)에 한도를 넘기면 429. */
	public void check(Long memberId) {
		AtomicInteger counter = counters.get(memberId, id -> new AtomicInteger());
		if (counter.incrementAndGet() > ratePerSecond) {
			throw new CourseException(CourseErrorCode.COURSE_DRAFT_TOO_FREQUENT);
		}
	}
}
