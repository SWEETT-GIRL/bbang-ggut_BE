package sweetgirl.croissant.dev.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.response.DevRequestLogListResponse;
import sweetgirl.croissant.dev.service.DevPerfService;
import sweetgirl.croissant.global.response.ApiResponse;

@Profile({"local", "staging"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/dev/perf")
public class DevPerfController {

	private final DevPerfService devPerfService;

	@GetMapping("/request-logs")
	public ApiResponse<DevRequestLogListResponse> getRequestLogs(
			@RequestParam(defaultValue = "10") int sinceMinutes,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "slow") String sort,
			@RequestParam(required = false) String endpoint) {
		return ApiResponse.ok(devPerfService.getRequestLogs(sinceMinutes, size, sort, endpoint));
	}
}
