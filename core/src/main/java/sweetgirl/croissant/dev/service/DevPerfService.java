package sweetgirl.croissant.dev.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.response.DevEndpointStatsListResponse;
import sweetgirl.croissant.dev.dto.response.DevEndpointStatsResponse;
import sweetgirl.croissant.dev.dto.response.DevRequestLogListResponse;
import sweetgirl.croissant.dev.dto.response.DevRequestLogResponse;
import sweetgirl.croissant.global.logging.dto.projection.RequestTimingProjection;
import sweetgirl.croissant.global.logging.entity.RequestLog;
import sweetgirl.croissant.global.logging.repository.RequestLogRepository;

@Profile({"local", "staging", "prod"})
@Service
@RequiredArgsConstructor
public class DevPerfService {

	private static final int MAX_SIZE = 200;
	/** 통계 창 상한 — 하루. request_log 는 하루 최대 23만 행이라 그 이상은 투영 조회라도 무겁다. */
	static final int MAX_STATS_MINUTES = 1440;
	static final int MAX_STATS_LIMIT = 100;
	/** FE 의 api_request_slow 기준(1초)과 같게 — 두 지표를 나란히 놓고 읽을 수 있어야 한다. */
	static final int SLOW_THRESHOLD_MS = 1000;

	private final RequestLogRepository requestLogRepository;

	public DevRequestLogListResponse getRequestLogs(int sinceMinutes, int size, String sort, String endpoint) {
		int normalizedSinceMinutes = Math.max(1, sinceMinutes);
		int normalizedSize = Math.max(1, Math.min(size, MAX_SIZE));
		String normalizedEndpoint = StringUtils.hasText(endpoint) ? endpoint : null;
		LocalDateTime createdAtAfter = LocalDateTime.now().minusMinutes(normalizedSinceMinutes);
		PageRequest pageRequest = PageRequest.of(0, normalizedSize);

		List<RequestLog> logs = "recent".equals(sort)
			? requestLogRepository.findRecentLogs(createdAtAfter, normalizedEndpoint, pageRequest)
			: requestLogRepository.findSlowLogs(createdAtAfter, normalizedEndpoint, pageRequest);

		List<Integer> durations = logs.stream()
			.map(RequestLog::getResponseTimeMs)
			.filter(duration -> duration != null)
			.toList();
		Integer avgDurationMs = durations.isEmpty()
			? null
			: (int)Math.round(durations.stream().mapToInt(Integer::intValue).average().orElse(0));
		Integer maxDurationMs = durations.stream().mapToInt(Integer::intValue).max().stream().boxed().findFirst()
			.orElse(null);
		int successCount = (int)logs.stream()
			.filter(log -> log.getResponseStatus() != null
				&& log.getResponseStatus() >= 200
				&& log.getResponseStatus() < 400)
			.count();

		return new DevRequestLogListResponse(
			logs.size(),
			successCount,
			avgDurationMs,
			maxDurationMs,
			logs.stream().map(DevRequestLogResponse::from).toList());
	}

	/**
	 * 엔드포인트별 서버측 응답시간 통계. 경로는 {@link EndpointPathNormalizer} 로 접어 {@code /stores/{id}} 단위로 모으고,
	 * 요청 수 내림차순 limit 개를 준다. FE 로그(Amplitude)가 보는 "느린 요청"과 같은 1초 기준으로 slowCount 를 센다.
	 */
	public DevEndpointStatsListResponse getEndpointStats(int sinceMinutes, String endpoint, int limit) {
		int normalizedSinceMinutes = Math.max(1, Math.min(sinceMinutes, MAX_STATS_MINUTES));
		int normalizedLimit = Math.max(1, Math.min(limit, MAX_STATS_LIMIT));
		String endpointFilter = StringUtils.hasText(endpoint) ? EndpointPathNormalizer.normalize(endpoint) : null;
		LocalDateTime createdAtAfter = LocalDateTime.now().minusMinutes(normalizedSinceMinutes);

		Map<String, List<RequestTimingProjection>> timingsByEndpoint = new LinkedHashMap<>();
		for (RequestTimingProjection timing : requestLogRepository.findTimingsSince(createdAtAfter)) {
			String key = EndpointPathNormalizer.normalize(timing.getEndpoint());
			if (endpointFilter != null && !endpointFilter.equals(key)) {
				continue;
			}
			timingsByEndpoint.computeIfAbsent(key, ignored -> new ArrayList<>()).add(timing);
		}

		List<DevEndpointStatsResponse> stats = timingsByEndpoint.entrySet().stream()
			.map(entry -> summarize(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingInt(DevEndpointStatsResponse::count).reversed()
				.thenComparing(DevEndpointStatsResponse::endpoint))
			.limit(normalizedLimit)
			.toList();
		int totalRequests = timingsByEndpoint.values().stream().mapToInt(List::size).sum();
		return new DevEndpointStatsListResponse(normalizedSinceMinutes, totalRequests, stats);
	}

	private static DevEndpointStatsResponse summarize(String endpoint, List<RequestTimingProjection> timings) {
		int[] sortedDurations = timings.stream().mapToInt(RequestTimingProjection::getResponseTimeMs).sorted()
			.toArray();
		int slowCount = (int)timings.stream().filter(timing -> timing.getResponseTimeMs() >= SLOW_THRESHOLD_MS).count();
		int serverErrorCount = (int)timings.stream()
			.filter(timing -> timing.getResponseStatus() != null && timing.getResponseStatus() >= 500)
			.count();
		return new DevEndpointStatsResponse(
			endpoint,
			sortedDurations.length,
			percentile(sortedDurations, 0.50),
			percentile(sortedDurations, 0.95),
			percentile(sortedDurations, 0.99),
			sortedDurations[sortedDurations.length - 1],
			slowCount,
			serverErrorCount);
	}

	/** nearest-rank 백분위 — 정렬된 값에서 ceil(p·n) 번째. 보간하지 않으므로 항상 실제 관측값이다. */
	static int percentile(int[] sortedValues, double percentile) {
		int rank = (int)Math.ceil(percentile * sortedValues.length);
		return sortedValues[Math.max(0, Math.min(rank, sortedValues.length) - 1)];
	}
}
