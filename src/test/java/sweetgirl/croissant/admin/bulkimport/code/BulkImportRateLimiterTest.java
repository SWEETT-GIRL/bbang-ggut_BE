package sweetgirl.croissant.admin.bulkimport.code;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BulkImportRateLimiterTest {

	@Test
	@DisplayName("acquire를 연속 3번 호출하면 최소 2 * minIntervalMs 이상 시간이 소요된다")
	void acquire_enforcesMinInterval() throws InterruptedException {
		BulkImportRateLimiter limiter = new BulkImportRateLimiter(100);

		long start = System.currentTimeMillis();
		limiter.acquire();
		limiter.acquire();
		limiter.acquire();
		long elapsed = System.currentTimeMillis() - start;

		assertThat(elapsed).isGreaterThanOrEqualTo(200L);
	}

	@Test
	@DisplayName("첫 acquire는 즉시 통과한다")
	void acquire_firstCallReturnsImmediately() throws InterruptedException {
		BulkImportRateLimiter limiter = new BulkImportRateLimiter(500);

		long start = System.currentTimeMillis();
		limiter.acquire();
		long elapsed = System.currentTimeMillis() - start;

		assertThat(elapsed).isLessThan(100L);
	}
}
