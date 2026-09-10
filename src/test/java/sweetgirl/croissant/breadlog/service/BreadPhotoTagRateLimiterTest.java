package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.breadlog.config.BreadPhotoTagProperties;

@DisplayName("사진 분석 레이트리밋")
class BreadPhotoTagRateLimiterTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long OTHER_MEMBER_ID = 8L;

	private BreadPhotoTagProperties properties;
	private BreadPhotoTagRateLimiter breadPhotoTagRateLimiter;

	@BeforeEach
	void setUp() {
		properties = new BreadPhotoTagProperties();
		properties.setRateLimitPerWindow(3);
		breadPhotoTagRateLimiter = new BreadPhotoTagRateLimiter(properties);
	}

	@Test
	@DisplayName("한도까지는 통과하고 그 다음부터 막는다")
	void blocksAfterLimit() {
		assertThat(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).isTrue();
		assertThat(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).isTrue();
		assertThat(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).isTrue();

		assertThat(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).isFalse();
	}

	@Test
	@DisplayName("한 사람이 막혀도 다른 사람은 안 막힌다 — 멤버마다 따로 센다")
	void countsPerMember() {
		for (int attempt = 0; attempt < 4; attempt++) {
			breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID);
		}

		assertThat(breadPhotoTagRateLimiter.tryAcquire(OTHER_MEMBER_ID)).isTrue();
	}

	@Test
	@DisplayName("구간이 지나면 다시 통과한다")
	void resetsAfterWindow() throws InterruptedException {
		properties.setRateLimitWindow(Duration.ofMillis(50));
		for (int attempt = 0; attempt < 4; attempt++) {
			breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID);
		}
		assertThat(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).isFalse();

		Thread.sleep(60);

		assertThat(breadPhotoTagRateLimiter.tryAcquire(MEMBER_ID)).isTrue();
	}
}
