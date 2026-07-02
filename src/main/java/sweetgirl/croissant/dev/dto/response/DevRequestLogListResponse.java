package sweetgirl.croissant.dev.dto.response;

import java.util.List;

public record DevRequestLogListResponse(
		int count,
		int successCount,
		Integer avgDurationMs,
		Integer maxDurationMs,
		List<DevRequestLogResponse> logs) {
}
