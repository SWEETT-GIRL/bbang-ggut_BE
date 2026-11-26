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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fail closed for unverified values; a normal empty observation never becomes a deletion. */
@Component
@RequiredArgsConstructor
public class AutomaticApplyPolicy {
	public static final String VERSION = "live-capture-auto-v1";
	private final ObjectMapper mapper;

	public void verify(ChangeProposal proposal, AutomaticCandidate candidate, long storeId,
			Map<String, ProvenanceSource> provenance) {
		if (proposal.getStoreId() != storeId || proposal.getId() != candidate.proposalId()
			|| !Objects.equals(proposal.getTarget(), candidate.target())
			|| proposal.getSource() != candidate.source()
			|| proposal.getProducer() != ProposalProducer.NAVER_SNAPSHOT_RECONCILE
			|| !json(proposal.getOldValue()).equals(json(candidate.oldValue()))
			|| !json(proposal.getNewValue()).equals(json(candidate.newValue()))) hold("CANDIDATE_CONFLICT");
		String note = Objects.toString(proposal.getReviewNote(), "");
		if (note.contains("SOURCE_CONFLICT") || note.contains("PRODUCER_CONFLICT")
			|| note.contains("다른 값을 제안")) hold("SOURCE_CONFLICT");
		String target = candidate.target();
		if (candidate.source() != ProvenanceSource.NAVER && candidate.source() != ProvenanceSource.BAEMIN)
			hold("SOURCE_UNSUPPORTED");
		String field = target.startsWith("store.") ? target.substring(6) : target;
		ProvenanceSource previous = provenance.get(field);
		if (previous == null && target.matches("menu\\.\\d+\\..+"))
			previous = provenance.get(target.substring(0, target.lastIndexOf('.')));
		if (previous == ProvenanceSource.ADMIN || previous == ProvenanceSource.USER) hold("PROTECTED_SOURCE");
		if (previous != null && previous != candidate.source()) hold("SOURCE_CONFLICT");
		if (candidate.source() == ProvenanceSource.BAEMIN && !target.startsWith("menu.add.") && previous == null)
			hold("SOURCE_UNKNOWN");
		JsonNode value = json(candidate.newValue());
		JsonNode old = json(candidate.oldValue());
		if (target.startsWith("menu.add.")) {
			if (!value.isObject() || !text(value.path("name")) || value.path("name").asString().length() > 255
				|| !(value.path("price").isNull() || price(value.path("price")))) hold("INVALID_MENU");
			return;
		}
		if (target.matches("menu\\.\\d+\\.price")) {
			if (!price(value)) hold("INVALID_PRICE");
			return;
		}
		if (target.matches("menu\\.\\d+\\.description")) {
			if (!text(value) || value.asString().length() > 255) hold("INVALID_DESCRIPTION");
			return;
		}
		if ("store.phone".equals(target)) {
			if (!text(value) || !value.asString().matches("(?:0[0-9-]{8,13}|1[0-9]{3}-[0-9]{4})")) hold("INVALID_PHONE");
			return;
		}
		if ("store.instagram_handle".equals(target)) {
			if (!text(value) || !value.asString().matches("[A-Za-z0-9._]{1,30}")) hold("INVALID_INSTAGRAM");
			return;
		}
		if ("store.name".equals(target)) {
			String before = normalize(old.asString("")), after = normalize(value.asString(""));
			if (!text(value) || value.asString().length() > 255 || before.isEmpty() || after.isEmpty()
				|| !(before.contains(after) || after.contains(before))) hold("STORE_IDENTITY_CHANGE");
			return;
		}
		if ("store.business_hours".equals(target)) {
			if (!value.isArray() || value.isEmpty()) hold("INCOMPLETE_HOURS");
			Set<String> days = new HashSet<>();
			for (JsonNode row : value) {
				String day = row.path("day").asString("");
				if (!Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY").contains(day)
					|| !days.add(day) || !row.path("closed").isBoolean()) hold("INVALID_HOURS");
				if (!row.path("closed").asBoolean() && (!time(row.path("open")) || !time(row.path("close"))))
					hold("INCOMPLETE_HOURS");
			}
			return;
		}
		if ("store.address".equals(target)) {
			if (!text(value.path("address")) || !coordinates(old) || !coordinates(value)
				|| !value.path("distanceM").isNumber()) hold("LOCATION_EVIDENCE_REQUIRED");
			if (value.path("distanceM").asDouble() >= 1000 || value.path("distanceM").asDouble() < 0)
				hold("STORE_LOCATION_CHANGE");
			// The existing location gate must additionally resolve these exact coordinates.
			return;
		}
		if (target.matches("menu\\.\\d+\\.category")) {
			if (!text(value.path("category")) || !old.has("categoryId")) hold("CATEGORY_EVIDENCE_REQUIRED");
			return;
		}
		if (target.matches("menu\\.\\d+\\.removed")) {
			if (!value.isNull() || !text(old.path("name")) || !old.has("price") || !old.has("description")
				|| old.hasNonNull("reason")) hold("DELETION_EVIDENCE_REQUIRES_REVIEW");
			// MenuRemovalProposalGate verifies complete, latest absence before claim.
			return;
		}
		hold("FIELD_EVIDENCE_REQUIRED");
	}

	private JsonNode json(String raw) {
		try { return mapper.readTree(raw == null ? "null" : raw); }
		catch (RuntimeException invalid) { throw new AutomaticApplyHold("INVALID_JSON"); }
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
	private void hold(String reason) { throw new AutomaticApplyHold(reason); }
}
