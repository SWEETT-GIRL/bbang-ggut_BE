package sweetgirl.dataops.replay;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

class CaptureReplayServiceTest {
	static final String HASH = "a".repeat(64);
	final ObjectMapper mapper = new ObjectMapper();
	final NaverPlaceParser parser = new NaverPlaceParser();
	final ResponseDiagnostics diagnostics = new ResponseDiagnostics(mapper, parser, new BusinessHoursParser(), new MenuEvidenceParser(mapper));
	final ReplayJournal journal = mock(ReplayJournal.class);
	final ReconcileService reconcile = mock(ReconcileService.class);
	final StoreObservationBarrier barrier = mock(StoreObservationBarrier.class);
	final StoreSyncStateRepository states = mock(StoreSyncStateRepository.class);
	final VerifiedReplayAuthority authority = mock(VerifiedReplayAuthority.class);
	@SuppressWarnings("unchecked") final ObjectProvider<VerifiedReplayAuthority> authorities = mock(ObjectProvider.class);
	final HashMap<String, ReplayJournal.ItemReceipt> receipts = new HashMap<>();
	CaptureReplayService service;
	ReplayPlan plan;
	ReplayJournal.Capture capture;
	StoreSyncState state;

	@BeforeEach void setup() {
		String raw = raw(mapper);
		OffsetDateTime observed = OffsetDateTime.now().minusMinutes(1);
		capture = new ReplayJournal.Capture("capture-1", 1, "NAVER", "123", observed, observed.plusSeconds(10), 0L,
			raw, ResponseDigests.sha256(raw), "old-parser", "old-contract");
		String parsedDigest = ResponseDigests.sha256(ResponseDigests.canonical(mapper.valueToTree(parser.parse(mapper.readTree(raw))), mapper));
		var target = new ReplayPlan.Target(capture.captureId(), capture.payloadDigest(), 1, "123", observed.toInstant(), 0L, parsedDigest);
		plan = new ReplayPlan(UUID.randomUUID(), HASH, "release", HASH, diagnostics.runtimeParserDigest(),
			diagnostics.structureProfile().digest(), ResponseDiagnostics.CONTRACT, HASH, Instant.now().plusSeconds(300), List.of(target), List.of(target.captureId()));
		state = StoreSyncState.builder().storeId(1L).source(SyncSource.NAVER).externalId("123").build();
		when(authorities.getIfAvailable()).thenReturn(authority);
		when(authority.require(any(), anyString())).thenAnswer(call -> plan);
		when(authority.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("release", HASH));
		when(reconcile.replayPolicyDigest()).thenReturn(HASH);
		when(states.findForSnapshotUpdate(1L, SyncSource.NAVER)).thenReturn(Optional.of(state));
		when(journal.capture("capture-1")).thenAnswer(call -> Optional.of(capture));
		when(journal.latest(any())).thenReturn(true);
		when(journal.receipt(any(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(receipts.get(call.getArgument(2))));
		doAnswer(call -> { receipts.put(call.getArgument(1), call.getArgument(3)); return null; }).when(journal).append(any(), anyString(), any(), any(), anyList());
		when(journal.canaryPassed(any())).thenAnswer(call -> receipts.containsKey("CANARY") && "PARSED".equals(receipts.get("CANARY").status()));
		when(reconcile.reconcileCapturedReplay(anyLong(), any(), anyBoolean())).thenReturn(List.of());
		var manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new CaptureReplayService(authorities, journal, barrier, states, diagnostics, parser, reconcile, mapper, manager);
	}

	static String raw(ObjectMapper mapper) {
		var payload = mapper.createObjectNode();
		payload.set("PlaceDetailBase:123", mapper.readTree("{\"id\":\"123\",\"name\":\"빵집\"}"));
		payload.putObject("ROOT_QUERY").set("placeDetail({\"input\":{\"id\":\"123\"}})", mapper.readTree("""
			{"placeMenus":{"items":[{"__ref":"PlaceMenuItem:a"}],"menuCount":1},
			 "newBusinessHours":[{"businessHours":[{"day":"매일","businessHours":{"start":"09:00","end":"18:00"}}]}]}
			"""));
		payload.set("PlaceMenuItem:a", mapper.readTree("""
			{"id":"a","__typename":"PlaceMenuItem","name":"빵","price":{"displayText":"5,300원"},"images":[{"url":"image"}]}
			"""));
		return ResponseDigests.canonical(payload, mapper);
	}

	@Test void missing_or_stale_approval_has_no_journal_or_reconcile_side_effects() {
		when(authorities.getIfAvailable()).thenReturn(null);
		assertThatThrownBy(() -> service.canary(plan.operationId(), HASH)).hasMessage("AUTHENTICATED_REPLAY_APPROVAL_REQUIRED");
		verifyNoInteractions(journal, barrier, states);
		verify(reconcile, never()).reconcileCapturedReplay(any(), any(), anyBoolean());
	}
	@Test void runtime_mismatch_never_starts_replay() {
		when(authority.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("other-release", HASH));
		assertThatThrownBy(() -> service.canary(plan.operationId(), HASH)).hasMessage("REPLAY_RUNTIME_MISMATCH");
		verifyNoInteractions(journal);
	}
	@Test void canary_must_precede_expansion() {
		assertThatThrownBy(() -> service.replay(plan.operationId(), HASH)).hasMessage("CANARY_NOT_PASSED");
		verify(reconcile, never()).reconcileCapturedReplay(any(), any(), anyBoolean());
	}
	@Test void canary_and_replay_preserve_original_raw_state_and_duplicate_receipts() {
		var original = capture;
		long attempt = state.getAttemptSeq();
		var canary = service.canary(plan.operationId(), HASH);
		assertThat(canary.passed()).isTrue();
		assertThat(canary.operatingWrites()).isZero();
		verify(reconcile, never()).reconcileCapturedReplay(any(), any(), anyBoolean());
		var first = service.replay(plan.operationId(), HASH);
		var repeated = service.replay(plan.operationId(), HASH);
		assertThat(first).isEqualTo(repeated);
		assertThat(first.items().get(0).status()).isEqualTo("NO_CHANGE");
		assertThat(capture).isEqualTo(original);
		assertThat(state.getAttemptSeq()).isEqualTo(attempt);
		verify(reconcile, times(1)).reconcileCapturedReplay(eq(1L), any(), eq(true));
		verify(states, never()).save(any());
		verify(journal, times(2)).append(any(), anyString(), any(), any(), anyList());
	}
	@Test void newer_raw_after_successful_canary_blocks_proposal_generation() {
		service.canary(plan.operationId(), HASH);
		when(journal.latest(any())).thenReturn(false);
		var result = service.replay(plan.operationId(), HASH);
		assertThat(result.passed()).isFalse();
		assertThat(result.items().get(0).reason()).isEqualTo("STALE_CAPTURE");
		verify(reconcile, never()).reconcileCapturedReplay(any(), any(), anyBoolean());
	}
	@Test void later_attempt_or_identity_change_cannot_replay_old_raw() {
		ReflectionTestUtils.setField(state, "attemptSeq", 1L);
		assertThat(service.canary(plan.operationId(), HASH).items().get(0).status()).isEqualTo("STALE");
		verify(reconcile, never()).reconcileCapturedReplay(any(), any(), anyBoolean());
	}
	@Test void tampered_raw_or_original_metadata_is_held() {
		capture = new ReplayJournal.Capture(capture.captureId(), 1, "NAVER", "123", capture.observedAt(), capture.receivedAt(), 0L,
			"{}", capture.payloadDigest(), "old-parser", "old-contract");
		assertThat(service.canary(plan.operationId(), HASH).items().get(0).reason()).isEqualTo("CAPTURE_PROVENANCE_MISMATCH");
	}
	@Test void candidate_result_mismatch_fails_canary_without_proposals() {
		var target = plan.targets().get(0);
		plan = withTargets(List.of(new ReplayPlan.Target(target.captureId(), target.payloadDigest(), 1, "123", target.observedAt(), 0L, HASH)));
		assertThat(service.canary(plan.operationId(), HASH).items().get(0).reason()).isEqualTo("CANDIDATE_RESULT_MISMATCH");
		verify(reconcile, never()).reconcileCapturedReplay(any(), any(), anyBoolean());
	}
	@Test void created_and_reused_proposals_are_distinct_and_removal_is_explicitly_held() {
		var created = new ReconcileService.ReplayProposal(11L, true, "NAVER_SNAPSHOT_RECONCILE", null, UUID.randomUUID(), "store.name", "old", "new", null);
		var reused = new ReconcileService.ReplayProposal(12L, false, "LEGACY_UNKNOWN", 88L, UUID.randomUUID(), "menu.9.removed", "old", null,
			"REPLAY_MENU_REMOVAL_REQUIRES_MANIFEST_GATE");
		when(reconcile.reconcileCapturedReplay(anyLong(), any(), anyBoolean())).thenReturn(List.of(created, reused));
		service.canary(plan.operationId(), HASH);
		var receipt = service.replay(plan.operationId(), HASH).items().get(0);
		assertThat(receipt.createdProposalIds()).containsExactly(11L);
		assertThat(receipt.reusedProposalIds()).containsExactly(12L);
		assertThat(receipt.reason()).isEqualTo("REPLAY_MENU_REMOVAL_REQUIRES_MANIFEST_GATE");
	}
	@Test void more_than_twenty_canary_targets_is_rejected() {
		var ids = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "capture-" + i).toList();
		var targets = ids.stream().map(id -> new ReplayPlan.Target(id, HASH, 1, "123", Instant.now(), 0L, HASH)).toList();
		assertThatThrownBy(() -> new ReplayPlan(plan.operationId(), HASH, "release", HASH, plan.parserDigest(), plan.profileDigest(),
			plan.contractVersion(), HASH, plan.expiresAt(), targets, ids)).isInstanceOf(IllegalArgumentException.class);
	}
	private ReplayPlan withTargets(List<ReplayPlan.Target> targets) {
		return new ReplayPlan(plan.operationId(), plan.manifestDigest(), plan.releaseId(), plan.artifactDigest(), plan.parserDigest(),
			plan.profileDigest(), plan.contractVersion(), plan.policyDigest(), plan.expiresAt(), targets, plan.canaryCaptureIds());
	}
}
