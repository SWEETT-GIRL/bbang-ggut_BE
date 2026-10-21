package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.enums.ProposalTargetFamily;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ReconcileMode;
import sweetgirl.dataops.reconcile.policy.BeverageFilter;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import sweetgirl.dataops.reconcile.policy.MergePolicy;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.dto.StoreCurrentValues;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in, private saved-raw evaluation. No Spring context, datasource, network client or model is created.
 * MENU_SCOPE_SAMPLE_DIR must contain diagnostic-input.jsonl and held-hours-comparison.json.
 * The committed test contains neither those raw responses nor saved serving values.
 */
@EnabledIfEnvironmentVariable(named = "RUN_MENU_SCOPE_SAVED_SAMPLE", matches = "true")
class MenuScopeSampleReplayTest {

	private static final EnumSet<ProposalTargetFamily> MENU_FAMILIES = EnumSet.of(
		ProposalTargetFamily.MENU_CONTENT, ProposalTargetFamily.MENU_REMOVAL,
		ProposalTargetFamily.MENU_CATEGORY);
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@SuppressWarnings("unchecked")
	void saved_100_responses_release_only_store_scope_and_reproduce_hours_candidates() throws Exception {
		long started = System.nanoTime();
		String inputDirectory = System.getenv("MENU_SCOPE_SAMPLE_DIR");
		assertThat(inputDirectory).as("Explicit private input directory is required").isNotBlank();
		Path inputs = Path.of(inputDirectory);
		Path rawFile = inputs.resolve("diagnostic-input.jsonl");
		Path comparisonFile = inputs.resolve("held-hours-comparison.json");
		List<String> rawLines = Files.readAllLines(rawFile).stream().filter(s -> !s.isBlank()).toList();
		JsonNode comparison = mapper.readTree(Files.readString(comparisonFile));
		Map<Long, JsonNode> savedHours = new LinkedHashMap<>();
		comparison.path("comparisons").forEach(row -> savedHours.put(row.path("store_id").asLong(), row));
		assertThat(rawLines).hasSize(100);
		assertThat(savedHours).hasSize(10);

		NaverPlaceParser parser = new NaverPlaceParser();
		BusinessHoursParser hoursParser = new BusinessHoursParser();
		ResponseDiagnostics diagnostics = new ResponseDiagnostics(mapper, parser, hoursParser,
			new MenuEvidenceParser(mapper));
		ServingReader serving = mock(ServingReader.class);
		ObjectProvider<ServingReader> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(serving);
		ProposalCommandService proposals = mock(ProposalCommandService.class);
		ChangeProposalRepository proposalRepository = mock(ChangeProposalRepository.class);
		StoreFieldProvenanceRepository provenance = mock(StoreFieldProvenanceRepository.class);
		MergePolicy policy = new MergePolicy();
		ReflectionTestUtils.setField(policy, "mode", ReconcileMode.PROPOSAL_ONLY);
		ReconcileService service = new ReconcileService(provider, proposals, policy, new BeverageFilter(),
			new MenuCategoryClassifier(), provenance, mock(StoreSyncStateRepository.class), proposalRepository,
			mock(PlaceSnapshotRepository.class), parser, hoursParser, diagnostics,
			mock(ResponseCaptureService.class), mock(PlatformTransactionManager.class), mapper);
		ReflectionTestUtils.setField(service, "enabled", true);

		List<Proposed> emitted = new ArrayList<>();
		when(proposals.propose(anyLong(), anyString(), any(), any(), any(), anyLong(), any(), any(), any()))
			.thenAnswer(call -> {
				long id = emitted.size() + 1L;
				emitted.add(new Proposed(call.getArgument(0), call.getArgument(1), call.getArgument(2),
					call.getArgument(3)));
				return new ChangeProposalResponse(id, call.getArgument(0), call.getArgument(1),
					call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5),
					ProposalStatus.PENDING, null, null, null);
			});
		// Controlled in-memory PENDING sentinels model the actual repository's family-scoped expiry.
		Map<Long, EnumSet<ProposalTargetFamily>> pendingMenuSentinels = new LinkedHashMap<>();
		when(proposalRepository.expireOutOfGeneration(anyLong(), any(), any(), any(), anyInt(), any()))
			.thenAnswer(call -> {
				Collection<ProposalTargetFamily> judged = call.getArgument(2);
				assertThat(judged).doesNotContainAnyElementsOf(MENU_FAMILIES);
				EnumSet<ProposalTargetFamily> remaining = pendingMenuSentinels.get(call.<Long>getArgument(0));
				int before = remaining.size();
				remaining.removeAll(judged);
				return before - remaining.size();
			});

		Map<String, Integer> assessments = new LinkedHashMap<>();
		List<Map<String, Object>> rows = new ArrayList<>();
		int partialWithSavedHours = 0;
		for (int i = 0; i < rawLines.size(); i++) {
			JsonNode input = mapper.readTree(rawLines.get(i));
			long storeId = input.path("storeId").asLong();
			SnapshotSubmitRequest request = new SnapshotSubmitRequest(storeId, SyncSource.NAVER,
				input.path("payload"), null, input.path("observationId").asString(),
				input.path("externalId").asString(), OffsetDateTime.parse(input.path("observedAt").asString()),
				input.path("attemptSeq").asLong());
			ResponseDiagnostics.Report report = diagnostics.inspect(request);
			assessments.merge(report.assessment(), 1, Integer::sum);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("sample", i + 1);
			row.put("assessment", report.assessment());
			row.put("issues", report.issues().stream().map(ResponseDiagnostics.Issue::code).distinct().toList());
			if (!report.held() && !report.menuAllowed()) {
				ParsedPlace parsed = parser.parse(request.payload());
				JsonNode saved = savedHours.get(storeId);
				List<BusinessHourRow> currentHours;
				if (saved != null) {
					partialWithSavedHours++;
					currentHours = hours(saved.path("current_hours"));
					assertThat(hoursParser.parse(parsed.businessHoursRaw()))
						.containsExactlyElementsOf(hours(saved.path("parsed_hours")));
				} else {
					// No saved serving comparison exists for these seven cases. Assert no readable hours;
					// the empty fixture is not presented as their real serving value.
					assertThat(hoursParser.parse(parsed.businessHoursRaw())).isEmpty();
					currentHours = List.of();
				}
				when(serving.findStore(storeId)).thenReturn(Optional.of(new StoreCurrentValues(storeId,
					parsed.name(), parsed.address(), parsed.phone(), "ACTIVE", parsed.instagramHandle(),
					parsed.longitude(), parsed.latitude())));
				when(serving.findBusinessHours(storeId)).thenReturn(currentHours);
				pendingMenuSentinels.put(storeId, EnumSet.copyOf(MENU_FAMILIES));
				int before = emitted.size();
				service.reconcile(storeId, (long)i + 1, parsed, report.menuAllowed());
				List<Proposed> produced = List.copyOf(emitted.subList(before, emitted.size()));
				assertThat(produced).allMatch(p -> "store.business_hours".equals(p.target()));
				if (saved != null) {
					String expected = saved.path("comparison").asString();
					if ("same".equals(expected)) assertThat(produced).isEmpty();
					else assertThat(produced).hasSizeLessThanOrEqualTo(1);
					row.put("saved_hours_comparison", expected);
					if (!"same".equals(expected) && produced.isEmpty()) {
						List<String> parsedDays = hoursParser.parse(parsed.businessHoursRaw()).stream()
							.map(BusinessHourRow::dayOfWeek).toList();
						row.put("existing_days_absent_from_parsed", currentHours.stream()
							.map(BusinessHourRow::dayOfWeek).filter(day -> !parsedDays.contains(day)).toList());
					}
				} else {
					assertThat(produced).isEmpty();
					row.put("saved_hours_comparison", "not_available_no_readable_hours");
				}
				row.put("hours_proposals", produced.size());
				row.put("menu_pending_sentinels_preserved", pendingMenuSentinels.get(storeId).size());
			}
			rows.add(row);
		}

		assertThat(assessments).containsExactlyInAnyOrderEntriesOf(Map.of("READY", 76, "PARTIAL", 17, "HELD", 7));
		assertThat(partialWithSavedHours).isEqualTo(10);
		// The original direct comparison found five candidates. One differs only by an
		// unread Tuesday: the real overlay policy preserves it and correctly emits no proposal.
		assertThat(emitted).hasSize(4);
		assertThat(emitted).allMatch(p -> "store.business_hours".equals(p.target()));
		assertThat(pendingMenuSentinels).hasSize(17);
		assertThat(pendingMenuSentinels.values()).allSatisfy(families -> assertThat(families).isEqualTo(MENU_FAMILIES));
		verify(serving, never()).findMenus(anyLong());
		verify(proposalRepository, times(17)).expireOutOfGeneration(anyLong(), any(), any(), any(), anyInt(), any());
		Map<String, Long> proposalKinds = new LinkedHashMap<>();
		emitted.forEach(p -> proposalKinds.merge(savedHours.get(p.storeId()).path("comparison").asString(), 1L, Long::sum));
		assertThat(proposalKinds).containsExactlyInAnyOrderEntriesOf(Map.of("different", 2L, "currently_empty", 2L));
		List<Map<String, Object>> differencesWithoutProposal = rows.stream()
			.filter(row -> "different".equals(row.get("saved_hours_comparison"))
				&& Integer.valueOf(0).equals(row.get("hours_proposals"))).toList();
		assertThat(differencesWithoutProposal).singleElement().satisfies(row ->
			assertThat(row.get("existing_days_absent_from_parsed")).isEqualTo(List.of("TUESDAY")));

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("evaluation", "saved100-diagnostics-and-scoped-reconcile");
		summary.put("input_sha256", ResponseDigests.sha256(Files.readString(rawFile)));
		summary.put("hours_comparison_sha256", ResponseDigests.sha256(Files.readString(comparisonFile)));
		summary.put("diagnostic_version", ResponseDiagnostics.VERSION);
		summary.put("assessments", assessments);
		summary.put("scoped_reconcile_executions", 17);
		summary.put("actual_hours_propose_calls", emitted.size());
		summary.put("previous_direct_comparison_candidates", 5);
		summary.put("hours_proposal_kinds", proposalKinds);
		summary.put("menu_propose_calls", 0);
		summary.put("menu_pending_sentinels_preserved", 51);
		summary.put("duration_ms", (System.nanoTime() - started) / 1_000_000);
		summary.put("production_writes", 0);
		summary.put("new_crawls", 0);
		summary.put("model_calls", 0);
		summary.put("limitations", List.of(
			"Actual ResponseDiagnostics and ReconcileService executed; ServingReader and persistence are mocks.",
			"Only the ten saved hours comparisons use recorded serving values; other store fields are controlled equal-value fixtures.",
			"Provenance is absent in this controlled fixture; prior proposal suppression/conflict rules in ProposalCommandService are not executed.",
			"Actual propose calls can be fewer than the prior direct-array comparison: Reconcile overlays readable weekdays on existing hours.",
			"The emitted proposals are real ReconcileService calls, not persisted or approved proposals.",
			"Snapshot ingestion, state/hash updates, signal preservation and approval gates are covered by separate tests, not this evaluation."));
		summary.put("samples", rows);
		Path output = Path.of(System.getenv().getOrDefault("MENU_SCOPE_RESULT_DIR",
			"artifacts/response-agent/menu-scope-20261013"));
		Files.createDirectories(output);
		Files.writeString(output.resolve("saved-sample-replay.json"),
			mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n");
	}

	private List<BusinessHourRow> hours(JsonNode node) {
		List<BusinessHourRow> result = new ArrayList<>();
		node.forEach(row -> result.add(new BusinessHourRow(row.path("dayOfWeek").asString(),
			row.path("openTime").asString(null), row.path("closeTime").asString(null), row.path("closed").asBoolean())));
		return result;
	}

	private record Proposed(long storeId, String target, String oldValue, String newValue) {}
}
