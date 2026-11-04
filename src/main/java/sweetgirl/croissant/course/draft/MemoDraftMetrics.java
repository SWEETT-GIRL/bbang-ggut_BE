package sweetgirl.croissant.course.draft;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.signal.CourseSubscriptionRegistry;

/**
 * 초안 버퍼 계측. 측정 계획의 보조 숫자가 전부 여기서 나온다 — 합치기 비율(쓰기 수 : 반영 수), 플러시 지연·묶음 크기, backlog, 임대 회수, 소켓 세션.
 *
 * <ul>
 * <li>{@code draft_write_total{result=accepted|stale}} — PUT 이 받아들인/옛 seq 로 무시한 수</li>
 * <li>{@code draft_flush_total} · {@code draft_flush_batch_size} — 코스 반영 횟수와 한 번에 반영한 초안 수.
 * 합치기 비율 = write 합 / flush_batch_size 합</li>
 * <li>{@code draft_flush_lag_seconds} — 초안이 마지막으로 쓰인 시각 → DB 반영 시각</li>
 * <li>{@code draft_backlog} — 반영을 기다리는 코스 수(Redis 를 스크랩마다 한 번 읽는다. 실패하면 -1)</li>
 * <li>{@code draft_inflight_reclaimed_total} — 임대 만료로 되돌린 코스 수(죽은 플러셔·느린 반영의 증거)</li>
 * <li>{@code draft_socket_sessions} — 이 인스턴스에 붙은 코스 신호 소켓 세션 수</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = CourseDraftConfig.REDIS_MODE_PROPERTY)
public class MemoDraftMetrics {

	private final Counter accepted;
	private final Counter stale;
	private final Counter flushes;
	private final Counter reclaimed;
	private final DistributionSummary batchSize;
	private final Timer flushLag;

	public MemoDraftMetrics(MeterRegistry registry, MemoDraftBuffer buffer, CourseSubscriptionRegistry subscriptions) {
		this.accepted = Counter.builder("draft_write_total").tag("result", "accepted")
			.description("받아들인 초안 쓰기").register(registry);
		this.stale = Counter.builder("draft_write_total").tag("result", "stale")
			.description("옛 seq 라 무시한 초안 쓰기").register(registry);
		this.flushes = Counter.builder("draft_flush_total").description("코스 단위 DB 반영 횟수").register(registry);
		this.reclaimed = Counter.builder("draft_inflight_reclaimed_total").description("임대 만료로 되돌린 코스 수")
			.register(registry);
		this.batchSize = DistributionSummary.builder("draft_flush_batch_size").description("한 번에 반영한 초안 수")
			.publishPercentiles(0.5, 0.95).register(registry);
		this.flushLag = Timer.builder("draft_flush_lag_seconds").description("초안 마지막 쓰기 → DB 반영")
			.publishPercentiles(0.5, 0.95).register(registry);
		Gauge.builder("draft_backlog", buffer, MemoDraftMetrics::backlogOf).description("반영을 기다리는 코스 수")
			.register(registry);
		Gauge.builder("draft_socket_sessions", subscriptions, CourseSubscriptionRegistry::sessionCount)
			.description("이 인스턴스의 코스 신호 소켓 세션 수").register(registry);
	}

	public void recordWrite(boolean wasAccepted) {
		(wasAccepted ? accepted : stale).increment();
	}

	/** @param entries 이번에 반영한 초안들. 지연은 각 초안의 마지막 쓰기 시각 기준 */
	public void recordFlush(List<DraftEntry> entries, long flushedAtMs) {
		flushes.increment();
		batchSize.record(entries.size());
		for (DraftEntry entry : entries) {
			flushLag.record(Duration.ofMillis(Math.max(0, flushedAtMs - entry.updatedAt())));
		}
	}

	public void recordReclaimed(long count) {
		reclaimed.increment(count);
	}

	private static double backlogOf(MemoDraftBuffer buffer) {
		try {
			return buffer.backlog();
		} catch (DataAccessException exception) {
			return -1;
		}
	}
}
