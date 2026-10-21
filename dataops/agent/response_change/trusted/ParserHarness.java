import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Fixed runner: expected results stay in the parent process, outside this container. */
public final class ParserHarness {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
            .selectors(selectClass("sweetgirl.dataops.snapshot.parser.NaverPlaceParserTest")).build());
        var summary = listener.getSummary();
        Map<String, Object> regression = new LinkedHashMap<>();
        regression.put("found", summary.getTestsFoundCount());
        regression.put("succeeded", summary.getTestsSucceededCount());
        regression.put("failed", summary.getTestsFailedCount());
        regression.put("skipped", summary.getTestsSkippedCount());
        regression.put("failures", summary.getFailures().stream().map(failure ->
            Map.of("test", failure.getTestIdentifier().getDisplayName(),
                   "error", String.valueOf(failure.getException()))).toList());
        JsonNode cases = mapper.readTree(Files.readString(Path.of("/input/fixtures.json")));
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode fixture : cases) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", fixture.path("id").asString());
            try {
                ParsedPlace parsed = new NaverPlaceParser().parse(fixture.path("raw"));
                result.put("actual", parsed);
            } catch (Throwable exception) {
                result.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
            results.add(result);
        }
        System.out.print(mapper.writeValueAsString(Map.of("regression", regression, "cases", results)));
    }
}
