package sweetgirl.dataops.autoapply;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.policy.BeverageFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fail closed for unverified values; a normal empty observation never becomes a deletion. */
@Component
@RequiredArgsConstructor
public class AutomaticApplyPolicy {
	public static final String VERSION = "live-capture-auto-v1";
	private final ObjectMapper mapper;
	private final BeverageFilter menuFilter = new BeverageFilter();

	public void verify(ChangeProposal proposal, AutomaticCandidate candidate, long storeId,
			Map<String, ProvenanceSource> provenance) {
		if (proposal.getStoreId() != storeId || proposal.getId() != candidate.proposalId()
			|| !Objects.equals(proposal.getTarget(), candidate.target())
			|| proposal.getSource() != candidate.source()
			|| proposal.getProducer() != ProposalProducer.NAVER_SNAPSHOT_RECONCILE
			|| !json(proposal.getOldValue()).equals(json(candidate.oldValue()))
			|| !json(proposal.getNewValue()).equals(json(candidate.newValue()))) hold(AutomaticApplyException.Reason.CANDIDATE_CONFLICT);
		String note = Objects.toString(proposal.getReviewNote(), "");
		if (note.contains("SOURCE_CONFLICT") || note.contains("PRODUCER_CONFLICT")
			|| note.contains("다른 값을 제안")) hold(AutomaticApplyException.Reason.SOURCE_CONFLICT);
		verifyProvenance(candidate, provenance);
		verifyValue(candidate);
	}

	private void verifyProvenance(AutomaticCandidate candidate, Map<String, ProvenanceSource> provenance) {
		String target = candidate.target();
		if (candidate.source() != ProvenanceSource.NAVER && candidate.source() != ProvenanceSource.BAEMIN)
			hold(AutomaticApplyException.Reason.SOURCE_UNSUPPORTED);
		String field = target.startsWith("store.") ? target.substring(6) : target;
		ProvenanceSource previous = provenance.get(field);
		if (target.matches("menu\\.\\d+\\..+")) {
			ProvenanceSource menuSource = provenance.get(target.substring(0, target.lastIndexOf('.')));
			if (menuSource == ProvenanceSource.ADMIN || menuSource == ProvenanceSource.USER)
				hold(AutomaticApplyException.Reason.PROTECTED_SOURCE);
			if (previous == null) previous = menuSource;
		}
		if (previous == ProvenanceSource.ADMIN || previous == ProvenanceSource.USER) hold(AutomaticApplyException.Reason.PROTECTED_SOURCE);
		if (target.matches("menu\\.\\d+\\.removed")) {
			String prefix = target.substring(0, target.lastIndexOf('.') + 1);
			for (var entry : provenance.entrySet()) {
				if (!entry.getKey().startsWith(prefix)) continue;
				if (entry.getValue() == ProvenanceSource.ADMIN || entry.getValue() == ProvenanceSource.USER)
					hold(AutomaticApplyException.Reason.PROTECTED_SOURCE);
				if (entry.getValue() != candidate.source()) hold(AutomaticApplyException.Reason.SOURCE_CONFLICT);
			}
		}
		if (previous != null && previous != candidate.source()) hold(AutomaticApplyException.Reason.SOURCE_CONFLICT);
		if (candidate.source() == ProvenanceSource.BAEMIN && !target.startsWith("menu.add.") && previous == null)
			hold(AutomaticApplyException.Reason.SOURCE_UNKNOWN);
	}

	private void verifyValue(AutomaticCandidate candidate) {
		String target = candidate.target();
		JsonNode value = json(candidate.newValue());
		JsonNode old = json(candidate.oldValue());
		if (target.startsWith("menu.add.")) {
			if (menuFilter.isExcluded(value.path("name").asString(""), null))
				hold(AutomaticApplyException.Reason.MENU_POLICY_REVIEW_REQUIRED);
			if (!value.isObject() || !text(value.path("name")) || value.path("name").asString().length() > 255
				|| !(value.path("price").isNull() || price(value.path("price")))) hold(AutomaticApplyException.Reason.INVALID_MENU);
			return;
		}
		if (target.matches("menu\\.\\d+\\.price")) {
			if (!price(value)) hold(AutomaticApplyException.Reason.INVALID_PRICE);
			return;
		}
		if (target.matches("menu\\.\\d+\\.description")) {
			if (!text(value) || value.asString().length() > 255) hold(AutomaticApplyException.Reason.INVALID_DESCRIPTION);
			return;
		}
		if (target.startsWith("store.")) { verifyStoreValue(target, old, value); return; }
		if (target.matches("menu\\.\\d+\\.category")) {
			if (!text(value.path("category")) || !old.has("categoryId")) hold(AutomaticApplyException.Reason.CATEGORY_EVIDENCE_REQUIRED);
			return;
		}
		if (target.matches("menu\\.\\d+\\.removed")) {
			// Only the explicit poke policy permits automatic exclusion without source disappearance.
			boolean policyExclusion = "제외 필터".equals(old.path("reason").asString(""));
			if (policyExclusion && !menuFilter.isPoke(old.path("name").asString("")))
				hold(AutomaticApplyException.Reason.MENU_POLICY_REVIEW_REQUIRED);
			if (!value.isNull() || !text(old.path("name")) || !old.has("price") || !old.has("description")
				|| (old.hasNonNull("reason") && !policyExclusion)) hold(AutomaticApplyException.Reason.DELETION_EVIDENCE_REQUIRES_REVIEW);
			// MenuRemovalProposalGate verifies complete, latest absence before claim.
			return;
		}
		hold(AutomaticApplyException.Reason.FIELD_EVIDENCE_REQUIRED);
	}

	private void verifyStoreValue(String target, JsonNode old, JsonNode value) {
		if ("store.phone".equals(target)) {
			if (!text(value) || !value.asString().matches("(?:0[0-9-]{8,13}|1[0-9]{3}-[0-9]{4})")) hold(AutomaticApplyException.Reason.INVALID_PHONE);
			return;
		}
		if ("store.instagram_handle".equals(target)) {
			if (!text(value) || !value.asString().matches("[A-Za-z0-9._]{1,30}")) hold(AutomaticApplyException.Reason.INVALID_INSTAGRAM);
			return;
		}
		if ("store.name".equals(target)) {
			String before = normalize(old.asString("")), after = normalize(value.asString(""));
			if (!text(value) || value.asString().length() > 255 || before.isEmpty() || after.isEmpty()
				|| !(before.contains(after) || after.contains(before))) hold(AutomaticApplyException.Reason.STORE_IDENTITY_CHANGE);
			return;
		}
		if ("store.business_hours".equals(target)) { verifyHours(value); return; }
		if ("store.address".equals(target)) {
			if (!text(value.path("address")) || !coordinates(old) || !coordinates(value)
				|| !value.path("distanceM").isNumber()) hold(AutomaticApplyException.Reason.LOCATION_EVIDENCE_REQUIRED);
			if (value.path("distanceM").asDouble() >= 1000 || value.path("distanceM").asDouble() < 0)
				hold(AutomaticApplyException.Reason.STORE_LOCATION_CHANGE);
			// The existing location gate must additionally resolve these exact coordinates.
			return;
		}
		hold(AutomaticApplyException.Reason.FIELD_EVIDENCE_REQUIRED);
	}

	private void verifyHours(JsonNode value) {
		if (!value.isArray() || value.isEmpty()) hold(AutomaticApplyException.Reason.INCOMPLETE_HOURS);
		Set<String> days = new HashSet<>();
		for (JsonNode row : value) {
			String day = row.path("day").asString("");
			if (!Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY").contains(day)
				|| !days.add(day) || !row.path("closed").isBoolean()) hold(AutomaticApplyException.Reason.INVALID_HOURS);
			if (!row.path("closed").asBoolean() && (!time(row.path("open")) || !time(row.path("close"))))
				hold(AutomaticApplyException.Reason.INCOMPLETE_HOURS);
		}
	}

	private JsonNode json(String raw) {
		try { return mapper.readTree(raw == null ? "null" : raw); }
		catch (RuntimeException invalid) { throw new AutomaticApplyException.Hold(AutomaticApplyException.Reason.INVALID_JSON); }
	}
	private boolean price(JsonNode value) {
		return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0;
	}
	private boolean text(JsonNode value) { return value.isString() && !value.asString().isBlank(); }
	private boolean coordinates(JsonNode value) {
		return value.path("lon").isNumber() && value.path("lat").isNumber()
			&& Math.abs(value.path("lon").asDouble()) <= 180 && Math.abs(value.path("lat").asDouble()) <= 90;
	}
	private boolean time(JsonNode value) { return text(value) && value.asString().matches("(?:(?:[01][0-9]|2[0-3]):[0-5][0-9]|24:00)"); }
	private String normalize(String name) { return name.replace("까페", "카페").replaceAll("[^0-9A-Za-z가-힣]", "").toLowerCase(java.util.Locale.ROOT); }
	private void hold(AutomaticApplyException.Reason reason) { throw new AutomaticApplyException.Hold(reason); }
}
