package sweetgirl.dataops.snapshot.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

/** Offline audit: no Spring context, database, serving writer or model client is constructed. */
public final class ResponseDiagnosticCli {
	private ResponseDiagnosticCli() {}
	public static void main(String[] args) throws Exception {
		if (args.length != 2) throw new IllegalArgumentException("input.jsonl output.jsonl required");
		var mapper = new ObjectMapper();
		var diagnostic = new ResponseDiagnostics(mapper, new NaverPlaceParser(), new BusinessHoursParser(), new MenuEvidenceParser(mapper));
		try (var input = Files.newBufferedReader(Path.of(args[0])); var output = Files.newBufferedWriter(Path.of(args[1]))) {
			String line;
			while ((line = input.readLine()) != null) {
				var node = mapper.readTree(line);
				var request = new SnapshotSubmitRequest(node.path("storeId").asLong(), SyncSource.NAVER, node.path("payload"),
					null, node.path("observationId").asString(), node.path("externalId").asString(),
					OffsetDateTime.parse(node.path("observedAt").asString()), null);
				// Availability is a measurement of extracted output, not proof of field correctness.
				var availability = new java.util.LinkedHashMap<String, Object>();
				try {
					var parsed = ResponseDiagnostics.parsePreservingInput(new NaverPlaceParser(), request.payload());
					availability.put("name", parsed.name() != null && !parsed.name().isBlank());
					availability.put("phone", parsed.phone() != null && !parsed.phone().isBlank());
					availability.put("address", parsed.address() != null && !parsed.address().isBlank());
					availability.put("instagram", parsed.instagramHandle() != null && !parsed.instagramHandle().isBlank());
					availability.put("menuCount", parsed.menus().size());
					var hours = new BusinessHoursParser().parse(parsed.businessHoursRaw());
					availability.put("hoursRows", hours.size());
					availability.put("hoursValues", hours);
				} catch (RuntimeException e) {
					availability.put("errorType", e.getClass().getSimpleName());
				}
				var report = diagnostic.inspect(request);
				output.write(mapper.writeValueAsString(Map.of("sourceSnapshotId", node.path("sourceSnapshotId").asLong(),
					"observationId", request.observationId(), "parsedAvailability", availability,
					"report", report, "assessment", report.assessment(), "menuAllowed", report.menuAllowed(), "payloadDigest", ResponseDigests.sha256(ResponseDigests.canonical(request.payload(), mapper)))));
				output.newLine();
			}
		}
	}
}
