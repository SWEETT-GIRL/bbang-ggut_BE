package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.exception.CourseException;

/**
 * 회원당 초당 PUT memo-draft 횟수 제한. 앱은 0.5초 디바운스라 정상이면 초당 2회를 넘지 않는다.
 *
 * <p>기존 {@code RateLimitFilter} 는 IP 기준·1분 창·인증 전 필터라 회원을 모른다 — 통신사 NAT 뒤 여러 명이 한도를 나눠 쓰게 된다.
 *
 * <p>카운터는 초안 Redis 에 둔다. 인스턴스 메모리에만 두면 서버가 N대일 때 한도가 N배가 된다 — 2대에 번갈아 초당 20회를 보내니
 * 19회가 통과했다(2026-11-10 측정: 1대 197 통과/204 거절 → 2대 379 통과/22 거절). Redis 를 못 쓰면 인스턴스 카운터로 물러난다:
 * 한도가 느슨해질 뿐 제한기가 장애 원인이 되지는 않는다(어차피 그때 초안 쓰기 자체가 503 이다).
 */
@Slf4j
@Component
@ConditionalOnExpression(CourseDraftConfig.REDIS_CONFIGURED)
public class MemoDraftRateLimiter {

	static final String KEY_PREFIX = "draft:rate:";
	private static final Duration WINDOW = Duration.ofSeconds(1);
	/** 창의 첫 요청에만 만료를 건다 — INCR 과 PEXPIRE 사이에 끊겨 만료 없는 키가 남지 않게 한 번에. */
	private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
		"local c = redis.call('INCR', KEYS[1]) if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return c",
		Long.class);

	private final int ratePerSecond;
	private final Cache<Long, AtomicInteger> counters;
	/** 회원 id → 이번 창의 누적 횟수(공유). {@code null} 이면 인스턴스 카운터만 쓴다. */
	private final LongUnaryOperator sharedCounter;

	/** 생성자가 여럿(테스트용 주입)이라 Spring 이 쓸 쪽을 가리킨다 — 없으면 기본 생성자를 찾다 죽는다. */
	@Autowired
	public MemoDraftRateLimiter(CourseDraftProperties properties, MemoDraftRedis memoDraftRedis) {
		this(properties, Ticker.systemTicker(), redisCounter(memoDraftRedis.template()));
	}

	MemoDraftRateLimiter(CourseDraftProperties properties, Ticker ticker) {
		this(properties, ticker, null);
	}

	MemoDraftRateLimiter(CourseDraftProperties properties, Ticker ticker, LongUnaryOperator sharedCounter) {
		this.ratePerSecond = properties.getRatePerSecond();
		this.sharedCounter = sharedCounter;
		this.counters = Caffeine.newBuilder()
			.expireAfterWrite(WINDOW)
			.maximumSize(100_000)
			.ticker(ticker)
			.build();
	}

	private static LongUnaryOperator redisCounter(StringRedisTemplate redis) {
		return memberId -> {
			Long count = redis.execute(INCREMENT, List.of(KEY_PREFIX + memberId), Long.toString(WINDOW.toMillis()));
			return count == null ? 0 : count;
		};
	}

	/** 1초 창(첫 요청 기준)에 한도를 넘기면 429. */
	public void check(Long memberId) {
		if (countOf(memberId) > ratePerSecond) {
			throw new CourseException(CourseErrorCode.COURSE_DRAFT_TOO_FREQUENT);
		}
	}

	private long countOf(Long memberId) {
		if (sharedCounter != null) {
			try {
				return sharedCounter.applyAsLong(memberId);
			} catch (DataAccessException exception) {
				log.debug("[메모 초안] 제한 카운터 Redis 실패 — 인스턴스 카운터로 cause={}", exception.toString());
			}
		}
		return counters.get(memberId, id -> new AtomicInteger()).incrementAndGet();
	}
}
