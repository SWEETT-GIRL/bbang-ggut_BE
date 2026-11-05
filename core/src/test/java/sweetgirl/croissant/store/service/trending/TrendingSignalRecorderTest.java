package sweetgirl.croissant.store.service.trending;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.enums.TrendingSignal;

@ExtendWith(MockitoExtension.class)
class TrendingSignalRecorderTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 14, 30);
	private static final List<String> KEYS = List.of("seen:SAVE:10:7:20261005", "trend:h:2026100514",
		"trend:uv:10:20261005");
	private static final String SEEN_TTL_MS = Long.toString(TrendingSignalRecorder.SEEN_TTL.toMillis());
	private static final String BUCKET_TTL_MS = Long.toString(TrendingSignalRecorder.BUCKET_TTL.toMillis());

	@Mock
	private StringRedisTemplate redisTemplate;

	private final StoreTrendingProperties properties = new StoreTrendingProperties();
	private TrendingSignalRecorder recorder;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		recorder = new TrendingSignalRecorder(redisTemplate, properties);
	}

	@Test
	@DisplayName("판정·시간 버킷·그날 회원 수를 스크립트 한 번으로 기록한다 — 중간에 끊겨 판정만 남지 않게")
	void signal_isRecordedInOneScript() {
		recorder.record(7L, 10L, TrendingSignal.SAVE, AT);

		verify(redisTemplate).execute(TrendingSignalRecorder.RECORD_SCRIPT, KEYS, "10", "7", "2.0", SEEN_TTL_MS,
			BUCKET_TTL_MS);
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
		given(redisTemplate.execute(TrendingSignalRecorder.RECORD_SCRIPT, KEYS, "10", "7", "2.0", SEEN_TTL_MS,
			BUCKET_TTL_MS)).willThrow(new RedisConnectionFailureException("down"));

		recorder.record(7L, 10L, TrendingSignal.SAVE, AT);
	}
}
