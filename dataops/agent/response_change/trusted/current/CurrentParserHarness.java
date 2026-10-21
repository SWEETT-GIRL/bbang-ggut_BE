import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.MenuStructureProfile;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.StoreSignalParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Host-owned runner. Expected values and deployment credentials are never mounted. */
public final class CurrentParserHarness {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        var request = LauncherDiscoveryRequestBuilder.request();
        for (JsonNode name : mapper.readTree(Files.readString(Path.of("/input/test-classes.json")))) {
            request.selectors(selectClass(name.asString()));
        }
        launcher.execute(request.build());
        var summary = listener.getSummary();
        var regression = new LinkedHashMap<String, Object>();
        regression.put("found", summary.getTestsFoundCount());
        regression.put("succeeded", summary.getTestsSucceededCount());
        regression.put("failed", summary.getTestsFailedCount());
        regression.put("skipped", summary.getTestsSkippedCount());
        regression.put("aborted", summary.getTestsAbortedCount());
        regression.put("failures", summary.getFailures().stream().map(failure -> Map.of(
            "test", failure.getTestIdentifier().getDisplayName(), "error", String.valueOf(failure.getException()))).toList());

        var profile = MenuStructureProfile.bundled();
        var parser = new NaverPlaceParser(profile);
        var hours = new BusinessHoursParser();
        var evidence = new MenuEvidenceParser(mapper, profile);
        var diagnostics = new ResponseDiagnostics(mapper, parser, hours, evidence);
        var signalParser = new StoreSignalParser(parser, hours);
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode fixture : mapper.readTree(Files.readString(Path.of("/input/fixtures.json")))) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", fixture.path("id").asString());
            try {
                JsonNode raw = fixture.path("raw");
                String externalId = fixture.path("external_id").asString();
                JsonNode parserInput = raw.deepCopy();
                var actual = mapper.valueToTree(parser.parse(parserInput));
                if (!parserInput.equals(raw)) throw new IllegalStateException("PARSER_INPUT_MUTATION");
                row.put("parsed_digest", ResponseDigests.sha256(ResponseDigests.canonical(actual, mapper)));
                var report = diagnostics.inspect(new SnapshotSubmitRequest(1L, SyncSource.NAVER, raw.deepCopy(), null,
                    "isolated-fixed-case", externalId, OffsetDateTime.parse("2026-01-01T00:00:00Z"), 1L));
                var diagnosticFields = new LinkedHashMap<String, Object>();
                diagnosticFields.put("assessment", report.assessment());
                diagnosticFields.put("menuAllowed", report.menuAllowed());
                diagnosticFields.put("targetVerified", report.targetVerified());
                diagnosticFields.put("modelEligible", report.modelEligible());
                diagnosticFields.put("issueCodes", report.issues().stream().map(ResponseDiagnostics.Issue::code).sorted().toList());
                ((tools.jackson.databind.node.ObjectNode) actual).set("diagnostics", mapper.valueToTree(diagnosticFields));
                ((tools.jackson.databind.node.ObjectNode) actual).set("evidence", mapper.valueToTree(evidence.parse(raw.deepCopy(), externalId)));
                ((tools.jackson.databind.node.ObjectNode) actual).set("signals", mapper.valueToTree(signalParser.parse(raw.deepCopy())));
                row.put("actual", actual);
            } catch (Throwable error) {
                row.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            results.add(row);
        }

        var classes = new TreeMap<String, Object>();
        try (var files = Files.walk(Path.of("/tmp/classes/sweetgirl"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String relative = Path.of("/tmp/classes").relativize(file).toString();
                // Tests are protected validation inputs, never packaged in the service artifact.
                if (relative.contains("Test$") || relative.endsWith("Test.class")) continue;
                byte[] bytes = Files.readAllBytes(file);
                classes.put(relative, Map.of("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "bytes", Base64.getEncoder().encodeToString(bytes)));
            }
        }
        System.out.print(mapper.writeValueAsString(Map.of("regression", regression, "cases", results,
            "classes", classes, "profile_digest", profile.digest(), "parser_digest", diagnostics.runtimeParserDigest())));
    }
}
