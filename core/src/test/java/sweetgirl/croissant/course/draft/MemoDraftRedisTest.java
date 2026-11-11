package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;

class MemoDraftRedisTest {

	@Test
	@DisplayName("재연결 간격은 설정값으로 고정된다 — 몇 번째 시도든 같은 간격")
	void reconnectDelayIsConstant() {
		CourseDraftProperties.Redis redis = new CourseDraftProperties.Redis();
		redis.setReconnectDelay(Duration.ofMillis(250));

		ClientResources resources = MemoDraftRedis.clientResourcesOf(redis);
		try {
			assertThat(resources.reconnectDelay().createDelay(1)).isEqualTo(Duration.ofMillis(250));
			assertThat(resources.reconnectDelay().createDelay(12)).isEqualTo(Duration.ofMillis(250));
		} finally {
			resources.shutdown(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
	}

	@Test
	@DisplayName("기본 재연결 간격 250ms · 임대 5초")
	void defaults() {
		CourseDraftProperties properties = new CourseDraftProperties();

		assertThat(properties.getRedis().getReconnectDelay()).isEqualTo(Duration.ofMillis(250));
		assertThat(properties.getLease()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	@DisplayName("끊긴 동안의 명령은 기본으로 쌓아 두고, 설정을 켜면 바로 거절한다 — 재연결은 어느 쪽이든 켜져 있다")
	void disconnectedBehavior_followsSetting() {
		CourseDraftProperties.Redis redis = new CourseDraftProperties.Redis();

		assertThat(MemoDraftRedis.clientOptionsOf(redis).getDisconnectedBehavior())
			.isEqualTo(ClientOptions.DisconnectedBehavior.DEFAULT);

		redis.setRejectWhenDisconnected(true);
		ClientOptions options = MemoDraftRedis.clientOptionsOf(redis);
		assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
		assertThat(options.isAutoReconnect()).isTrue();
	}

	private static MemoDraftRedis redisResettingAfter(LettuceConnectionFactory connectionFactory, int timeouts) {
		return new MemoDraftRedis(mock(ClientResources.class), connectionFactory, timeouts);
	}

	@Test
	@DisplayName("명령 타임아웃이 연달아 문턱만큼 나면 연결을 한 번 다시 맺는다 — 멈춘 Redis 에서 새 마스터로 넘어가기 위해")
	void consecutiveTimeouts_resetConnectionOnce() {
		LettuceConnectionFactory connectionFactory = mock(LettuceConnectionFactory.class);
		MemoDraftRedis redis = redisResettingAfter(connectionFactory, 3);

		redis.commandTimedOut();
		redis.commandTimedOut();
		then(connectionFactory).should(never()).resetConnection();

		redis.commandTimedOut();
		then(connectionFactory).should(times(1)).resetConnection();

		// 방금 다시 맺었다 — 바로 또 타임아웃이 쏟아져도 최소 간격 안에는 다시 끊지 않는다
		redis.commandTimedOut();
		redis.commandTimedOut();
		redis.commandTimedOut();
		then(connectionFactory).should(times(1)).resetConnection();
	}

	@Test
	@DisplayName("중간에 성공이 끼면 다시 센다 — 가끔 나는 타임아웃으로는 연결을 끊지 않는다")
	void successBetweenTimeouts_resetsCount() {
		LettuceConnectionFactory connectionFactory = mock(LettuceConnectionFactory.class);
		MemoDraftRedis redis = redisResettingAfter(connectionFactory, 3);

		redis.commandTimedOut();
		redis.commandTimedOut();
		redis.commandSucceeded();
		redis.commandTimedOut();
		redis.commandTimedOut();

		then(connectionFactory).should(never()).resetConnection();
	}

	@Test
	@DisplayName("문턱이 0 이면 타임아웃이 아무리 나도 연결을 건드리지 않는다")
	void zeroThreshold_disabled() {
		LettuceConnectionFactory connectionFactory = mock(LettuceConnectionFactory.class);
		MemoDraftRedis redis = redisResettingAfter(connectionFactory, 0);

		for (int count = 0; count < 100; count++) {
			redis.commandTimedOut();
		}

		then(connectionFactory).should(never()).resetConnection();
	}

	@Test
	@DisplayName("다시 맺기가 실패해도 예외를 밖으로 내지 않는다 — 요청은 원래의 타임아웃으로 실패한다")
	void resetFailure_swallowed() {
		LettuceConnectionFactory connectionFactory = mock(LettuceConnectionFactory.class);
		willThrow(new IllegalStateException("boom")).given(connectionFactory).resetConnection();
		MemoDraftRedis redis = redisResettingAfter(connectionFactory, 1);

		assertThatCode(redis::commandTimedOut).doesNotThrowAnyException();
	}
}
