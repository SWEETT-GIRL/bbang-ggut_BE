package sweetgirl.croissant.course.draft;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Ticker;

/** Caffeine 만료를 기다리지 않고 돌리기 위한 손으로 감는 시계. */
class FakeTicker implements Ticker {

	private long nanos;

	@Override
	public long read() {
		return nanos;
	}

	void advance(Duration duration) {
		nanos += duration.toNanos();
	}
}
