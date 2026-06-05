package sweetgirl.croissant.admin.bulkimport.code;

public class BulkImportRateLimiter {

	private final long minIntervalMs;
	private long lastCallMs = 0L;

	public BulkImportRateLimiter(long minIntervalMs) {
		this.minIntervalMs = minIntervalMs;
	}

	public synchronized void acquire() throws InterruptedException {
		long now = System.currentTimeMillis();
		long waitMs = (lastCallMs + minIntervalMs) - now;
		if (waitMs > 0) {
			Thread.sleep(waitMs);
		}
		lastCallMs = System.currentTimeMillis();
	}
}
