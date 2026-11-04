package sweetgirl.croissant.store.service.trending;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.enums.TrendingSignal;

/**
 * 회원 행동 하나를 급부상 원재료로 Redis 에 센다. 같은 회원이 같은 가게에 같은 행동을 하루에 여러 번 해도 한 번만 센다 — 평소 수준
 * (user_store_interaction)도 회원×가게×종류×일 단위라 눈금을 맞춘다.
 *
 * <p>판정과 집계는 Lua 한 번으로 묶는다. 왕복을 나누면 Redis 가 중간에 끊겼을 때 판정만 남아 그날 신호가 다시 세지지 않고, TTL 없는 키가 남는다.
 *
 * <p>실패해도 던지지 않는다. 신호 몇 건을 잃는 것이 조회·찜 요청을 깨뜨리는 것보다 낫다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrendingSignalRecorder {

	/** 7일 창 + 하루 여유 */
	static final Duration BUCKET_TTL = Duration.ofDays(8);
	/** 하루 한 번 판정 — 자정을 넘는 요청까지 덮게 하루보다 조금 길게 */
	static final Duration SEEN_TTL = Duration.ofHours(26);
	static final RedisScript<Long> RECORD_SCRIPT = recordScript();

	private final StringRedisTemplate redisTemplate;
	private final StoreTrendingProperties properties;

	public void record(Long memberId, Long storeId, TrendingSignal signal, LocalDateTime occurredAt) {
		if (!properties.isEnabled() || memberId == null || storeId == null) {
			return;
		}
		double weight = properties.weightOf(signal);
		if (weight <= 0) {
			return;
		}
		try {
			redisTemplate.execute(RECORD_SCRIPT,
				List.of(
					TrendingKeys.seen(signal, storeId, memberId, occurredAt.toLocalDate()),
					TrendingKeys.hourBucket(occurredAt),
					TrendingKeys.uniqueMembers(storeId, occurredAt.toLocalDate())),
				storeId.toString(), memberId.toString(), Double.toString(weight),
				Long.toString(SEEN_TTL.toMillis()), Long.toString(BUCKET_TTL.toMillis()));
		} catch (RuntimeException exception) {
			log.warn("[급부상] 신호 기록 실패 store={} signal={} cause={}", storeId, signal, exception.toString());
		}
	}

	private static RedisScript<Long> recordScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/trending-record.lua"));
		script.setResultType(Long.class);
		return script;
	}
}
