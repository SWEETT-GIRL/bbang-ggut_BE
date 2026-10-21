package sweetgirl.croissant.global.logging.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.global.logging.dto.projection.RequestTimingProjection;
import sweetgirl.croissant.global.logging.entity.RequestLog;

@DataJpaTest
@Import(JpaConfig.class)
class RequestLogRepositoryTest {

	@Autowired
	private RequestLogRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private RequestLog save(String endpoint, Integer responseTimeMs, int status) {
		return repository.save(RequestLog.builder()
			.httpMethod("GET")
			.endpoint(endpoint)
			.responseStatus(status)
			.responseTimeMs(responseTimeMs)
			.build());
	}

	@Test
	@DisplayName("기간 안의 행만 세 칸으로 돌려주고, 응답시간이 없는 행과 기간 밖 행은 뺀다")
	void findTimingsSince_filtersWindowAndNullDurations() {
		save("/stores/autocomplete", 12, 200);
		save("/stores/7", 300, 500);
		save("/stores/autocomplete", null, 200);
		RequestLog old = save("/courses", 999, 200);
		entityManager.flush();
		jdbcTemplate.update("UPDATE request_log SET created_at = ? WHERE id = ?",
			Timestamp.valueOf(LocalDateTime.now().minusHours(3)), old.getId());

		List<RequestTimingProjection> timings = repository.findTimingsSince(LocalDateTime.now().minusMinutes(60));

		assertThat(timings).hasSize(2);
		assertThat(timings).extracting(RequestTimingProjection::getEndpoint)
			.containsExactlyInAnyOrder("/stores/autocomplete", "/stores/7");
		assertThat(timings).extracting(RequestTimingProjection::getResponseTimeMs).containsExactlyInAnyOrder(12, 300);
		assertThat(timings).extracting(RequestTimingProjection::getResponseStatus).containsExactlyInAnyOrder(200, 500);
	}
}
