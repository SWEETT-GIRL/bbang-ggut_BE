package sweetgirl.dataops.autoapply;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;

@RestController
@Tag(name = "[Admin] 수집 변경 자동 반영")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dataops.auto-apply.enabled", havingValue = "true")
public class AutomaticApplyController {
	private final AutomaticApplyCommandService service;
	@Operation(summary = "수집 변경 자동 반영", description = "수집 식별자에 연결된 변경 후보를 검증하고 운영 반영 결과를 반환합니다.")
	@PostMapping("/api/snapshots/{captureId}/auto-apply")
	public ApiResponse<AutomaticApplyResponse> apply(@PathVariable String captureId) {
		return ApiResponse.ok(AutomaticApplyResponse.from(service.apply(captureId)));
	}
}
