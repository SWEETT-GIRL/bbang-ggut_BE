package sweetgirl.dataops.proposal.policy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.dto.MenuCurrentValues;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.repository.MenuEvidenceRepository;
import sweetgirl.dataops.snapshot.repository.MenuEvidenceRepository.MenuApprovalCapture;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

class MenuRemovalProposalGateTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final MenuEvidenceRepository evidence = mock(MenuEvidenceRepository.class);
	private final PlaceSnapshotRepository snapshots = mock(PlaceSnapshotRepository.class);
	private final ServingReader reader = mock(ServingReader.class);
	private final MenuRemovalProposalGate gate = new MenuRemovalProposalGate(evidence, snapshots,
		new MenuEvidenceParser(mapper), new NaverPlaceParser(), mapper);
	private final LocalDateTime observedAt = LocalDateTime.of(2026, 10, 13, 22, 0);
	private ChangeProposal proposal;

	@BeforeEach
	void setup() {
		proposal = ChangeProposal.builder().storeId(1L).target("menu.77.removed")
			.oldValue("{\"name\":\"소금빵 3,500원\"}").source(ProvenanceSource.NAVER).snapshotId(10L).build();
		when(reader.findMenus(1L)).thenReturn(List.of(new MenuCurrentValues(77L, "소금빵 3,500원", 3500, null, false, null, null)));
		when(reader.findStoreByNaverPlaceId("123")).thenReturn(Optional.of(new ServingReader.StoreRef(1, "ACTIVE")));
		when(evidence.findExternalId(1, "NAVER")).thenReturn(Optional.of("123"));
		when(evidence.isLatestVerified("capture", 1, "NAVER", "123")).thenReturn(true);
		when(snapshots.findById(10L)).thenReturn(Optional.of(snapshot(raw(false, true))));
		latest(raw(false, true), "READY", true, true);
	}

	@Test
	void complete_origin_and_latest_target_scoped_absence_pass() {
		assertThatCode(() -> gate.verify(proposal, reader)).doesNotThrowAnyException();
	}

	@Test
	void latest_held_partial_or_received_cannot_reuse_older_complete_evidence() {
		for (String status : List.of("HELD", "PARTIAL", "RECEIVED")) {
			latest(raw(false, true), status, true, true);
			assertHeld();
		}
	}

	@Test
	void latest_incomplete_or_unverified_or_unrecorded_observation_holds() {
		latest(raw(false, true), "READY", false, true);
		assertHeld();
		latest(raw(false, true), "READY", true, false);
		assertHeld();
		when(evidence.latestCaptureForMenuApproval(1, "NAVER", "123")).thenReturn(Optional.empty());
		assertHeld();
	}

	@Test
	void menu_reappearing_under_normalized_name_holds() {
		latest(raw(true, true), "READY", true, true);
		assertHeld();
	}

	@Test
	void incomplete_origin_is_not_repaired_by_a_later_complete_observation() {
		when(snapshots.findById(10L)).thenReturn(Optional.of(snapshot(raw(false, false))));
		assertHeld();
	}

	@Test
	void legacy_without_origin_and_wrong_store_origin_hold() {
		when(snapshots.findById(10L)).thenReturn(Optional.empty());
		assertHeld();
		when(snapshots.findById(10L)).thenReturn(Optional.of(PlaceSnapshot.builder().storeId(2L)
			.source(SyncSource.NAVER).fetchedAt(observedAt).payload(raw(false, true)).build()));
		assertHeld();
	}

	@Test
	void legacy_origin_without_observed_time_holds_instead_of_throwing_null_pointer() {
		when(snapshots.findById(10L)).thenReturn(Optional.of(PlaceSnapshot.builder().storeId(1L)
			.source(SyncSource.NAVER).payload(raw(false, true)).build()));
		assertHeld();
	}

	@Test
	void latest_raw_and_evidence_must_agree_and_be_latest() {
		String raw = raw(false, true);
		when(evidence.latestCaptureForMenuApproval(1, "NAVER", "123")).thenReturn(Optional.of(
			new MenuApprovalCapture("capture", raw, "READY", observedAt, "hash", "different", true, true)));
		assertHeld();
		latest(raw, "READY", true, true);
		when(evidence.isLatestVerified("capture", 1, "NAVER", "123")).thenReturn(false);
		assertHeld();
	}

	@Test
	void changed_production_link_and_user_owned_menu_hold() {
		when(reader.findStoreByNaverPlaceId("123")).thenReturn(Optional.of(new ServingReader.StoreRef(9, "ACTIVE")));
		assertHeld();
		when(reader.findMenus(1L)).thenReturn(List.of(new MenuCurrentValues(77L, "소금빵 3,500원", 3500, null, true, null, null)));
		assertHeld();
	}

	@Test
	void origin_for_old_external_id_does_not_authorize_current_place_absence() {
		when(snapshots.findById(10L)).thenReturn(Optional.of(snapshot(raw(false, true).replace("123", "999"))));
		assertHeld();
	}

	@Test
	void policy_exclusion_is_explicitly_separate_from_source_absence() {
		ChangeProposal policy = ChangeProposal.builder().storeId(1L).target("menu.77.removed")
			.oldValue("{\"name\":\"아메리카노\",\"reason\":\"제외 필터\"}").source(ProvenanceSource.NAVER).build();
		clearInvocations(evidence, snapshots, reader);
		assertThatCode(() -> gate.verify(policy, null)).doesNotThrowAnyException();
		verifyNoInteractions(evidence, snapshots, reader);
	}

	private void assertHeld() {
		assertThatThrownBy(() -> gate.verify(proposal, reader)).isInstanceOf(ProposalException.class)
			.extracting("errorCode").isEqualTo(ProposalErrorCode.MENU_EVIDENCE_REQUIRED);
	}

	private PlaceSnapshot snapshot(String raw) {
		return PlaceSnapshot.builder().storeId(1L).source(SyncSource.NAVER).fetchedAt(observedAt.minusMinutes(1))
			.payload(raw).build();
	}

	private void latest(String raw, String status, boolean complete, boolean verified) {
		String digest = ResponseDigests.sha256(ResponseDigests.canonical(mapper.readTree(raw), mapper));
		when(evidence.latestCaptureForMenuApproval(1, "NAVER", "123")).thenReturn(Optional.of(
			new MenuApprovalCapture("capture", raw, status, observedAt, digest, digest, complete, verified)));
	}

	private String raw(boolean reappeared, boolean complete) {
		var raw = mapper.createObjectNode();
		var root = raw.putObject("ROOT_QUERY").putObject("placeDetail({\"input\":{\"id\":\"123\"}})");
		root.put("id", "123");
		root.putObject("base").put("__ref", "PlaceDetailBase:123");
		var menus = root.putObject("placeMenus");
		var items = menus.putArray("items");
		if (complete) menus.put("total", reappeared ? 1 : 0);
		raw.putObject("PlaceDetailBase:123").put("id", "123").put("name", "가게");
		if (reappeared) {
			items.addObject().put("__ref", "Menu:a");
			raw.putObject("Menu:a").put("__typename", "Menu").put("id", "a").put("name", "소금빵");
		}
		return raw.toString();
	}
}
