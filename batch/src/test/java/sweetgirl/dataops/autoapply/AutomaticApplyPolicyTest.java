package sweetgirl.dataops.autoapply;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import tools.jackson.databind.ObjectMapper;

class AutomaticApplyPolicyTest {
	private final AutomaticApplyPolicy policy = new AutomaticApplyPolicy(new ObjectMapper());
	private ChangeProposal proposal(String target, String oldValue, String newValue, ProvenanceSource source) {
		var proposal = ChangeProposal.builder().storeId(1L).target(target).oldValue(oldValue).newValue(newValue)
			.source(source).producer(ProposalProducer.NAVER_SNAPSHOT_RECONCILE).build();
		ReflectionTestUtils.setField(proposal, "id", 7L);
		return proposal;
	}
	private void verify(ChangeProposal proposal, Map<String, ProvenanceSource> provenance) {
		policy.verify(proposal, new AutomaticCandidate(7L, proposal.getTarget(), proposal.getOldValue(),
			proposal.getNewValue(), proposal.getSource()), 1L, provenance);
	}
	@Test void same_source_price_and_description_are_automatic() {
		for (var source : new ProvenanceSource[] {ProvenanceSource.NAVER, ProvenanceSource.BAEMIN}) {
			verify(proposal("menu.9.price", "3000", "3500", source), Map.of("menu.9", source));
			verify(proposal("menu.9.description", "null", "\"바삭한 빵\"", source), Map.of("menu.9.description", source));
		}
	}
	@Test void delivery_price_never_overwrites_unknown_or_other_source() {
		var proposal = proposal("menu.9.price", "3000", "3500", ProvenanceSource.BAEMIN);
		assertThatThrownBy(() -> verify(proposal, Map.of())).hasMessage("SOURCE_UNKNOWN");
		assertThatThrownBy(() -> verify(proposal, Map.of("menu.9", ProvenanceSource.NAVER))).hasMessage("SOURCE_CONFLICT");
		assertThatThrownBy(() -> verify(proposal, Map.of("menu.9.price", ProvenanceSource.USER))).hasMessage("PROTECTED_SOURCE");
	}
	@Test void null_negative_fractional_and_overflow_prices_are_held() {
		for (String value : new String[] {"null", "-1", "1.5", "2147483648", "\"3500\""}) {
			assertThatThrownBy(() -> verify(proposal("menu.9.price", "3000", value, ProvenanceSource.NAVER), Map.of()))
				.hasMessage("INVALID_PRICE");
		}
	}
	@Test void empty_values_and_deletion_stay_held() {
		assertThatThrownBy(() -> verify(proposal("menu.9.description", "\"빵\"", "null", ProvenanceSource.NAVER), Map.of()))
			.hasMessage("INVALID_DESCRIPTION");
		assertThatThrownBy(() -> verify(proposal("menu.9.removed", "{}", "null", ProvenanceSource.NAVER), Map.of()))
			.hasMessage("DELETION_EVIDENCE_REQUIRES_REVIEW");
	}
	@Test void current_capture_cannot_authorize_an_unrelated_candidate() {
		var proposal = proposal("menu.9.price", "3000", "3500", ProvenanceSource.NAVER);
		assertThatThrownBy(() -> policy.verify(proposal,
			new AutomaticCandidate(7, "menu.9.price", "3000", "4000", ProvenanceSource.NAVER), 1, Map.of()))
			.hasMessage("CANDIDATE_CONFLICT");
	}
	@Test void valid_hours_pass_but_incomplete_hours_do_not_delete_values() {
		verify(proposal("store.business_hours", "[]",
			"[{\"day\":\"MONDAY\",\"open\":\"09:00\",\"close\":\"18:00\",\"closed\":false}]", ProvenanceSource.NAVER), Map.of());
		assertThatThrownBy(() -> verify(proposal("store.business_hours", "[]", "[]", ProvenanceSource.NAVER), Map.of()))
			.hasMessage("INCOMPLETE_HOURS");
	}
	@Test void new_delivery_menu_is_allowed_without_replacing_an_existing_price() {
		verify(proposal("menu.add.test", null, "{\"name\":\"소금빵\",\"price\":3500}", ProvenanceSource.BAEMIN), Map.of());
	}
	@Test void food_classification_is_distinct_from_parser_repair_and_source_deletion_evidence() {
		for (String name : new String[] {"베이글 연어 포케", "베이글 새우 포케"}) {
			assertThatThrownBy(() -> verify(proposal("menu.9.removed",
				"{\"name\":\"" + name + "\",\"price\":11500,\"description\":\"\",\"reason\":\"제외 필터\"}",
				null, ProvenanceSource.NAVER), Map.of())).hasMessage("MENU_POLICY_REVIEW_REQUIRED");
		}
		// Missing historical provenance is not established by an empty current description.
		assertThatThrownBy(() -> verify(proposal("menu.9.description", "null", "\"설명\"", ProvenanceSource.BAEMIN), Map.of()))
			.hasMessage("SOURCE_UNKNOWN");
	}
	@Test void address_requires_bounded_distance_and_menu_deletion_requires_absence_evidence_shape() {
		verify(proposal("store.address", "{\"address\":\"이전\",\"lon\":127,\"lat\":37}",
			"{\"address\":\"새주소\",\"lon\":127.001,\"lat\":37,\"distanceM\":100}", ProvenanceSource.NAVER), Map.of());
		assertThatThrownBy(() -> verify(proposal("store.address", "{\"lon\":127,\"lat\":37}",
			"{\"address\":\"이전 의심\",\"lon\":128,\"lat\":37,\"distanceM\":90000}", ProvenanceSource.NAVER), Map.of()))
			.hasMessage("STORE_LOCATION_CHANGE");
		verify(proposal("menu.9.removed", "{\"name\":\"소금빵\",\"price\":3000,\"description\":null}", null, ProvenanceSource.NAVER), Map.of());
		assertThatThrownBy(() -> verify(proposal("menu.9.removed",
			"{\"name\":\"소금빵\",\"price\":3000,\"description\":null,\"reason\":\"POLICY\"}", null, ProvenanceSource.NAVER), Map.of()))
			.hasMessage("DELETION_EVIDENCE_REQUIRES_REVIEW");
	}
}
