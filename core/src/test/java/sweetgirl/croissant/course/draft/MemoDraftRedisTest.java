package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
