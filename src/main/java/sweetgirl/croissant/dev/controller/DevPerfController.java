package sweetgirl.croissant.dev.controller;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.response.DevRequestLogListResponse;
import sweetgirl.croissant.dev.service.DevPerfService;
import sweetgirl.croissant.global.response.ApiResponse;

@Profile({"local", "staging", "prod"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/dev/perf")
public class DevPerfController {

	private final DevPerfService devPerfService;
	private final Environment environment;

	@Value("${perf.dashboard.enabled:false}")
	private boolean perfDashboardEnabled;

	@Value("${perf.dashboard.access-key:}")
	private String perfDashboardAccessKey;

	@GetMapping("/request-logs")
	public ApiResponse<DevRequestLogListResponse> getRequestLogs(
			@RequestHeader(value = "X-Perf-Dashboard-Key", required = false) String accessKey,
			@RequestParam(defaultValue = "10") int sinceMinutes,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "slow") String sort,
			@RequestParam(required = false) String endpoint) {
		validateProdAccess(accessKey);
		return ApiResponse.ok(devPerfService.getRequestLogs(sinceMinutes, size, sort, endpoint));
	}

	private void validateProdAccess(String accessKey) {
		boolean prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
		if (!prodProfile) {
			return;
		}
		if (!perfDashboardEnabled || !StringUtils.hasText(perfDashboardAccessKey)
			|| !perfDashboardAccessKey.equals(accessKey)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "performance dashboard access denied");
		}
	}
}
