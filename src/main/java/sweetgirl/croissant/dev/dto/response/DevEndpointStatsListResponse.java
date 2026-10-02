package sweetgirl.croissant.dev.dto.response;

import java.util.List;

public record DevEndpointStatsListResponse(
		int sinceMinutes,
		int totalRequests,
		List<DevEndpointStatsResponse> endpoints) {
}
