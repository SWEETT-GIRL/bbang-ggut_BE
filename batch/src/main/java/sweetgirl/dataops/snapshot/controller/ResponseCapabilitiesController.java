package sweetgirl.dataops.snapshot.controller;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.autoapply.AutomaticApplyReadinessQueryService;

@RestController
@RequiredArgsConstructor
public class ResponseCapabilitiesController {
	private final ObjectProvider<ServingWriter> writer;
	private final AutomaticApplyReadinessQueryService readiness;
	@org.springframework.beans.factory.annotation.Value("${dataops.auto-apply.enabled:false}")
	private boolean automaticApplyEnabled;
	@GetMapping("/api/snapshots/capabilities")
	public ApiResponse<Map<String, Object>> capabilities() {
		return ApiResponse.ok(Map.of("captureProtocol", 1, "wholeObservationHold", true,
			"menuScopedHold", true, "writesEnabled", writer.getIfAvailable() != null,
			"automaticApplyProtocol", automaticApplyEnabled ? 1 : 0, "contractVersion", ResponseDiagnostics.CONTRACT,
			"automaticHoursPermissionsReady", automaticApplyEnabled && readiness.hoursPermissionsReady()));
	}
}
