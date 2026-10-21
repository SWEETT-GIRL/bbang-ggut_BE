package sweetgirl.dataops.snapshot.controller;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.ApiResponse;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;

@RestController
@RequiredArgsConstructor
public class ResponseCapabilitiesController {
	private final ObjectProvider<ServingWriter> writer;
	@GetMapping("/api/snapshots/capabilities")
	public ApiResponse<Map<String, Object>> capabilities() {
		return ApiResponse.ok(Map.of("captureProtocol", 1, "wholeObservationHold", true,
			"menuScopedHold", true, "writesEnabled", writer.getIfAvailable() != null, "contractVersion", ResponseDiagnostics.CONTRACT));
	}
}
