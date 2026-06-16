package sweetgirl.croissant.dev.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.response.DevRequestLogListResponse;
import sweetgirl.croissant.dev.dto.response.DevRequestLogResponse;
import sweetgirl.croissant.global.logging.entity.RequestLog;
import sweetgirl.croissant.global.logging.repository.RequestLogRepository;

@Profile({"local", "staging", "prod"})
@Service
@RequiredArgsConstructor
public class DevPerfService {

	private static final int MAX_SIZE = 200;

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
}
