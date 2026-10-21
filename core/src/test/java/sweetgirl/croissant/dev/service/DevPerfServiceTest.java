package sweetgirl.croissant.dev.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.dev.dto.response.DevEndpointStatsListResponse;
import sweetgirl.croissant.dev.dto.response.DevEndpointStatsResponse;
import sweetgirl.croissant.global.logging.dto.projection.RequestTimingProjection;
import sweetgirl.croissant.global.logging.repository.RequestLogRepository;

@ExtendWith(MockitoExtension.class)
class DevPerfServiceTest {

	@Mock
	private RequestLogRepository requestLogRepository;

	@InjectMocks
	private DevPerfService devPerfService;

	private record Timing(String endpoint, Integer responseTimeMs, Integer responseStatus)
			implements
				RequestTimingProjection {
		@Override
		public String getEndpoint() {
			return endpoint;
		}

		@Override
		public Integer getResponseTimeMs() {
			return responseTimeMs;
		}

		@Override
		public Integer getResponseStatus() {
			return responseStatus;
		}
	}

	@Test
	@DisplayName("숫자 세그먼트를 접어 같은 엔드포인트로 모으고, 요청 수 내림차순으로 준다")
	void groupsByNormalizedEndpointAndSortsByCount() {
		given(requestLogRepository.findTimingsSince(any())).willReturn(List.of(
			new Timing("/stores/12", 40, 200),
			new Timing("/stores/345", 60, 200),
			new Timing("/stores/autocomplete", 5, 200)));

		DevEndpointStatsListResponse response = devPerfService.getEndpointStats(60, null, 20);

		assertThat(response.totalRequests()).isEqualTo(3);
		assertThat(response.endpoints()).extracting(DevEndpointStatsResponse::endpoint)
			.containsExactly("/stores/{id}", "/stores/autocomplete");
		assertThat(response.endpoints().get(0).count()).isEqualTo(2);
		assertThat(response.endpoints().get(0).maxMs()).isEqualTo(60);
	}

	@Test
	@DisplayName("백분위는 nearest-rank — 1..100 에서 p50=50 · p95=95 · p99=99, 1초 이상과 5xx 를 따로 센다")
	void percentilesSlowAndErrors() {
		List<RequestTimingProjection> timings = IntStream.rangeClosed(1, 100)
			.mapToObj(i -> (RequestTimingProjection)new Timing("/courses", i == 100 ? 1500 : i, i <= 2 ? 503 : 200))
			.toList();
		given(requestLogRepository.findTimingsSince(any())).willReturn(timings);

		DevEndpointStatsResponse stats = devPerfService.getEndpointStats(60, null, 20).endpoints().get(0);

		assertThat(stats.count()).isEqualTo(100);
		assertThat(stats.p50Ms()).isEqualTo(50);
		assertThat(stats.p95Ms()).isEqualTo(95);
		assertThat(stats.p99Ms()).isEqualTo(99);
		assertThat(stats.maxMs()).isEqualTo(1500);
		assertThat(stats.slowCount()).isEqualTo(1);
		assertThat(stats.serverErrorCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("endpoint 를 주면 정규화해서 그 엔드포인트만 — /stores/1 로 물어도 /stores/{id} 가 나온다")
	void filtersByNormalizedEndpoint() {
		given(requestLogRepository.findTimingsSince(any())).willReturn(List.of(
			new Timing("/stores/12", 40, 200),
			new Timing("/stores/autocomplete", 5, 200)));

		DevEndpointStatsListResponse response = devPerfService.getEndpointStats(60, "/stores/1", 20);

		assertThat(response.totalRequests()).isEqualTo(1);
		assertThat(response.endpoints()).extracting(DevEndpointStatsResponse::endpoint).containsExactly("/stores/{id}");
	}

	@Test
	@DisplayName("창은 1~1440분, limit 은 1~100 으로 자른다")
	void clampsWindowAndLimit() {
		given(requestLogRepository.findTimingsSince(any())).willReturn(List.of(
			new Timing("/a", 1, 200), new Timing("/b", 1, 200), new Timing("/c", 1, 200)));
		ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);

		DevEndpointStatsListResponse response = devPerfService.getEndpointStats(99_999, null, 2);

		assertThat(response.sinceMinutes()).isEqualTo(DevPerfService.MAX_STATS_MINUTES);
		assertThat(response.endpoints()).hasSize(2);
		assertThat(response.totalRequests()).isEqualTo(3);
		org.mockito.BDDMockito.then(requestLogRepository).should().findTimingsSince(captor.capture());
		assertThat(captor.getValue()).isBefore(LocalDateTime.now().minusMinutes(DevPerfService.MAX_STATS_MINUTES - 1));
	}

	@Test
	@DisplayName("로그가 없으면 빈 목록 — 0 으로 나누지 않는다")
	void emptyWindow() {
		given(requestLogRepository.findTimingsSince(any())).willReturn(List.of());

		DevEndpointStatsListResponse response = devPerfService.getEndpointStats(0, null, 0);

		assertThat(response.sinceMinutes()).isEqualTo(1);
		assertThat(response.totalRequests()).isZero();
		assertThat(response.endpoints()).isEmpty();
	}
}
