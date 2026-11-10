package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
