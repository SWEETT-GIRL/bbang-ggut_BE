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
		validateAccess(accessKey);
		return ApiResponse.ok(devPerfService.getRequestLogs(sinceMinutes, size, sort, endpoint));
	}

	/**
	 * local 밖(staging·prod)에서는 대시보드 키가 있어야 한다. GET /dev/** 가 permitAll 이라 이 검사가 유일한 문인데,
	 * prod 만 보던 탓에 staging 의 요청 로그(엔드포인트·소요 시간·상태)가 비로그인으로 열려 있었다.
	 * 키는 cd-staging·cd-prod 가 PERF_DASHBOARD_ENABLED / ACCESS_KEY 로 내려준다 — 비어 있으면 그 환경은 닫힌다.
	 */
	private void validateAccess(String accessKey) {
		boolean localProfile = Arrays.asList(environment.getActiveProfiles()).contains("local");
		if (localProfile) {
			return;
		}
		if (!perfDashboardEnabled || !StringUtils.hasText(perfDashboardAccessKey)
			|| !perfDashboardAccessKey.equals(accessKey)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "performance dashboard access denied");
		}
	}
}
