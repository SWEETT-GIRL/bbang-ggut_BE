package sweetgirl.croissant.store.service.trending;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.enums.TrendingSignal;

@ExtendWith(MockitoExtension.class)
class TrendingSignalRecorderTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 14, 30);
	private static final String SEEN = "seen:SAVE:10:7:20261005";

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOperations;
	@Mock
	private ZSetOperations<String, String> zSetOperations;
	@Mock
	private HyperLogLogOperations<String, String> hyperLogLogOperations;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private TrendingSignalRecorder recorder;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		recorder = new TrendingSignalRecorder(redisTemplate, properties);
	}

	@Test
	@DisplayName("처음 보는 회원·가게·행동이면 그 시간 버킷에 가중치만큼 더하고 그날 회원 수에 넣는다")
	void firstSignalOfTheDay_isCounted() {
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
		given(redisTemplate.opsForHyperLogLog()).willReturn(hyperLogLogOperations);
		given(valueOperations.setIfAbsent(SEEN, "1", TrendingSignalRecorder.SEEN_TTL)).willReturn(true);

		recorder.record(7L, 10L, TrendingSignal.SAVE, AT);

		verify(zSetOperations).incrementScore("trend:h:2026100514", "10", 2.0);
		verify(redisTemplate).expire("trend:h:2026100514", TrendingSignalRecorder.BUCKET_TTL);
		verify(hyperLogLogOperations).add("trend:uv:10:20261005", "7");
	}

	@Test
	@DisplayName("같은 회원이 같은 가게에 같은 행동을 하루에 또 하면 세지 않는다 — 한 명이 새로고침으로 띄우지 못한다")
	void repeatedSignalOnSameDay_isIgnored() {
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(SEEN, "1", TrendingSignalRecorder.SEEN_TTL)).willReturn(false);

		recorder.record(7L, 10L, TrendingSignal.SAVE, AT);

		verify(redisTemplate, never()).opsForZSet();
	}

	@Test
	@DisplayName("비로그인(회원 없음)이거나 꺼져 있으면 아무것도 하지 않는다")
	void guestOrDisabled_doesNothing() {
		recorder.record(null, 10L, TrendingSignal.VIEW, AT);
		properties.setEnabled(false);
		recorder.record(7L, 10L, TrendingSignal.VIEW, AT);

		verifyNoInteractions(redisTemplate);
	}

	@Test
	@DisplayName("Redis 가 죽어 있어도 던지지 않는다 — 조회·찜 요청이 깨지면 안 된다")
	void redisFailure_isSwallowed() {
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.setIfAbsent(anyString(), eq("1"), eq(TrendingSignalRecorder.SEEN_TTL)))
			.willThrow(new RedisConnectionFailureException("down"));

		recorder.record(7L, 10L, TrendingSignal.VIEW, AT);
	}
}
