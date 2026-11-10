package sweetgirl.croissant.global.logging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.global.logging.LogEventType;
import sweetgirl.croissant.global.logging.entity.RequestLog;

@DataJpaTest
@Import(JpaConfig.class)
class RequestLogWriterTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private RequestLogWriter writer(JdbcTemplate template, int capacity) {
		return new RequestLogWriter(template, new TransactionTemplate(transactionManager), registry, capacity);
	}

	private static RequestLog log(String endpoint) {
		return RequestLog.builder().httpMethod("GET").endpoint(endpoint).responseStatus(200).build();
	}

	private double dropped(String reason) {
		return registry.counter("request_log_dropped_total", "reason", reason).count();
	}

	@Test
	@DisplayName("대기열에 쌓인 로그를 한 번에 쓰고, 모든 칸과 생성 시각이 들어간다")
	void flush_writesEveryColumn() {
		RequestLogWriter writer = writer(jdbcTemplate, 10);
		LocalDateTime before = LocalDateTime.now().minusSeconds(1);
		writer.enqueue(RequestLog.builder()
			.memberId(7L).httpMethod("GET").endpoint("/stores/1090").queryParams("lat=37.5").requestBody(null)
			.responseStatus(200).responseTimeMs(12).ipAddress("10.0.0.1").userAgent("okhttp").storeId(1090L)
			.eventType(LogEventType.STORE_VIEW).errorCode(null).errorMessage(null).sessionId("session-1")
			.build());
		writer.enqueue(log("/courses"));

		writer.flush();

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM request_log ORDER BY id");
		assertThat(rows).hasSize(2);
		Map<String, Object> view = rows.get(0);
		assertThat(view.get("member_id")).isEqualTo(7L);
		assertThat(view.get("endpoint")).isEqualTo("/stores/1090");
		assertThat(view.get("query_params")).isEqualTo("lat=37.5");
		assertThat(view.get("response_time_ms")).isEqualTo(12);
		assertThat(view.get("store_id")).isEqualTo(1090L);
		assertThat(view.get("event_type")).isEqualTo("STORE_VIEW");
		assertThat(view.get("session_id")).isEqualTo("session-1");
		assertThat(rows.get(1).get("member_id")).isNull();
		assertThat(rows.get(1).get("event_type")).isNull();
		Integer recent = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log WHERE created_at >= ?",
			Integer.class, before);
		assertThat(recent).isEqualTo(2);
		assertThat(registry.counter("request_log_written_total").count()).isEqualTo(2.0);
	}

	@Test
	@DisplayName("묶음 크기를 넘게 쌓여도 한 번의 flush 로 전부 쓴다")
	void flush_drainsMoreThanOneBatch() {
		int total = RequestLogWriter.BATCH_SIZE + 20;
		RequestLogWriter writer = writer(jdbcTemplate, total);
		for (int index = 0; index < total; index++) {
			writer.enqueue(log("/stores/autocomplete"));
		}

		writer.flush();

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(total);
	}

	@Test
	@DisplayName("대기열이 가득 차면 기다리지 않고 버리고, 버린 수를 센다")
	void enqueue_dropsWhenFull() {
		RequestLogWriter writer = writer(jdbcTemplate, 2);

		assertThat(writer.enqueue(log("/a"))).isTrue();
		assertThat(writer.enqueue(log("/b"))).isTrue();
		assertThat(writer.enqueue(log("/c"))).isFalse();

		assertThat(dropped("queue_full")).isEqualTo(1.0);
	}

	@Test
	@DisplayName("DB 쓰기가 실패하면 그 묶음은 버리고 예외를 밖으로 내지 않는다 — 다음 묶음은 다시 쓴다")
	void flush_swallowsWriteFailure() {
		JdbcTemplate failing = mock(JdbcTemplate.class);
		when(failing.batchUpdate(anyString(), anyList(), any(int[].class)))
			.thenThrow(new DataAccessResourceFailureException("connection is not available"))
			.thenReturn(new int[] {1});
		RequestLogWriter writer = writer(failing, 10);
		writer.enqueue(log("/a"));

		assertThatCode(writer::flush).doesNotThrowAnyException();
		assertThat(dropped("write_failed")).isEqualTo(1.0);

		writer.enqueue(log("/b"));
		writer.flush();

		verify(failing, times(2)).batchUpdate(anyString(), anyList(), any(int[].class));
		assertThat(registry.counter("request_log_written_total").count()).isEqualTo(1.0);
	}
}
