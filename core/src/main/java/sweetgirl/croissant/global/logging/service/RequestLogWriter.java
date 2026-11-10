package sweetgirl.croissant.global.logging.service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.logging.entity.RequestLog;

/**
 * 요청 로그를 모아서 쓴다 — 요청마다 INSERT 한 건(커넥션 한 번 · 커밋 한 번)이던 것을 0.5초마다 한 묶음으로.
 *
 * <p>왜: 편집자 1,280명 부하(초당 2,500건)에서 DB 커넥션 10개 중 최대 5개를 로그 스레드가 쥐었고, http 스레드의 23% 가 커넥션을
 * 기다렸다(11/10 덤프). 메모 초안이 타자를 Redis 로 모아도 공통 요청 로그가 요청당 INSERT 를 남기고 있었다. 여기서는 스레드
 * 하나가 커넥션 하나로, 묶음당 트랜잭션 하나로 쓴다.
 *
 * <p>요청 스레드는 대기열에 넣기만 한다(막히지 않는다). 대기열이 차면 버리고 센다 — 요청 로그는 버려도 되는 값이고, 잃으면
 * 복구 못 하는 신호는 signalExecutor 로 따로 간다. JPA 가 아니라 JDBC 로 쓰는 이유는 id 가 IDENTITY 라 Hibernate 가 INSERT 를
 * 묶지 못하기 때문이다. 그래서 생성 시각(@CreatedDate)은 대기열에 넣는 순간에 직접 찍는다.
 *
 * <p>종료할 때는 남은 대기열을 비우고 내려간다.
 */
@Slf4j
@Component
public class RequestLogWriter implements SmartLifecycle {

	static final int QUEUE_CAPACITY = 5_000;
	static final int BATCH_SIZE = 500;
	static final Duration TICK = Duration.ofMillis(500);

	/**
	 * 웹 서버(Boot 의 graceful shutdown 은 MAX_VALUE - 1024, 서버 정지는 MAX_VALUE - 2048)보다 늦게 멈추도록 낮은 단계에 둔다.
	 * 기본 단계(MAX_VALUE)면 가장 먼저 멈춰서, 처리 중이던 마지막 요청들의 로그가 대기열에 남은 채 버려진다.
	 */
	static final int PHASE = Integer.MAX_VALUE - 4096;

	private static final String INSERT_SQL = """
			INSERT INTO request_log (member_id, http_method, endpoint, query_params, request_body, response_status,
				response_time_ms, ip_address, user_agent, store_id, event_type, error_code, error_message, session_id,
				created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";
	private static final int[] ARGUMENT_TYPES = {Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
		Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.VARCHAR,
		Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP};

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactionTemplate;
	private final BlockingQueue<PendingLog> queue;
	private final Counter written;
	private final Counter droppedFull;
	private final Counter droppedFailed;

	private ThreadPoolTaskScheduler scheduler;
	private volatile boolean running;

	@Autowired
	public RequestLogWriter(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
			MeterRegistry registry) {
		this(jdbcTemplate, new TransactionTemplate(transactionManager), registry, QUEUE_CAPACITY);
	}

	RequestLogWriter(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, MeterRegistry registry,
			int queueCapacity) {
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = transactionTemplate;
		this.queue = new ArrayBlockingQueue<>(queueCapacity);
		this.written = Counter.builder("request_log_written_total").description("request_log 에 쓴 행 수")
			.register(registry);
		this.droppedFull = Counter.builder("request_log_dropped_total").tag("reason", "queue_full")
			.description("버린 요청 로그 수").register(registry);
		this.droppedFailed = Counter.builder("request_log_dropped_total").tag("reason", "write_failed")
			.description("버린 요청 로그 수").register(registry);
		Gauge.builder("request_log_queue_size", queue, BlockingQueue::size).description("쓰기를 기다리는 요청 로그 수")
			.register(registry);
	}

	/** 대기열에 넣는다. 가득 찼으면 버리고 false. 요청 스레드에서 불리므로 기다리지 않는다. */
	public boolean enqueue(RequestLog requestLog) {
		boolean accepted = queue.offer(new PendingLog(requestLog, LocalDateTime.now()));
		if (!accepted) {
			droppedFull.increment();
		}
		return accepted;
	}

	@Override
	public void start() {
		scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("request-log-writer-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(10);
		scheduler.initialize();
		scheduler.scheduleWithFixedDelay(this::flush, TICK);
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		if (scheduler != null) {
			scheduler.shutdown();
		}
		flush(); // 마지막 틱 뒤에 들어온 것까지 쓰고 내려간다
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}

	/** 대기열이 빌 때까지 묶음 단위로 쓴다. 쓰기에 실패한 묶음은 버린다 — 로그 때문에 다음 묶음이 막히면 안 된다. */
	void flush() {
		List<PendingLog> batch = new ArrayList<>(BATCH_SIZE);
		while (queue.drainTo(batch, BATCH_SIZE) > 0) {
			writeBatch(batch);
			batch.clear();
		}
	}

	private void writeBatch(List<PendingLog> batch) {
		List<Object[]> rows = batch.stream().map(RequestLogWriter::toRow).toList();
		try {
			transactionTemplate
				.executeWithoutResult(status -> jdbcTemplate.batchUpdate(INSERT_SQL, rows, ARGUMENT_TYPES));
			written.increment(rows.size());
		} catch (RuntimeException exception) {
			droppedFailed.increment(rows.size());
			log.warn("[요청 로그] {}건 쓰기 실패 — 버린다 cause={}", rows.size(), exception.toString());
		}
	}

	private static Object[] toRow(PendingLog pending) {
		RequestLog requestLog = pending.requestLog();
		return new Object[] {requestLog.getMemberId(), requestLog.getHttpMethod(), requestLog.getEndpoint(),
			requestLog.getQueryParams(), requestLog.getRequestBody(), requestLog.getResponseStatus(),
			requestLog.getResponseTimeMs(), requestLog.getIpAddress(), requestLog.getUserAgent(),
			requestLog.getStoreId(), requestLog.getEventType() == null ? null : requestLog.getEventType().name(),
			requestLog.getErrorCode(), requestLog.getErrorMessage(), requestLog.getSessionId(),
			Timestamp.valueOf(pending.createdAt())};
	}

	private record PendingLog(RequestLog requestLog, LocalDateTime createdAt) {
	}
}
