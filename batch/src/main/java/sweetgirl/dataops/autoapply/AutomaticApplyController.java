package sweetgirl.dataops.autoapply;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dataops.auto-apply.enabled", havingValue = "true")
public class AutomaticApplyController {
	private final AutomaticApplyService service;
	@PostMapping("/api/snapshots/{captureId}/auto-apply")
	public ApiResponse<AutomaticApplyService.Result> apply(@PathVariable String captureId) {
		return ApiResponse.ok(service.apply(captureId));
	}
}
