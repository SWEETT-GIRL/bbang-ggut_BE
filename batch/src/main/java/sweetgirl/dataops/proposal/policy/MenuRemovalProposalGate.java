package sweetgirl.dataops.proposal.policy;

import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import sweetgirl.dataops.snapshot.repository.MenuEvidenceRepository;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Source absence and an explicit exclusion policy are different reasons for removal. Absence requires both
 * the proposal's target-scoped complete raw and the latest accepted, complete observation at approval time.
 * A newer HELD/PARTIAL/RECEIVED capture cannot be hidden by looking only at successful observations.
 */
@Component
@RequiredArgsConstructor
public class MenuRemovalProposalGate {
	private static final Pattern TARGET = Pattern.compile("^menu\\.(\\d+)\\.removed$");
	private static final String POLICY_EXCLUSION = "제외 필터";
	private final MenuEvidenceRepository evidenceRepository;
	private final PlaceSnapshotRepository snapshotRepository;
	private final MenuEvidenceParser evidenceParser;
	private final NaverPlaceParser placeParser;
	private final ObjectMapper objectMapper;

	public boolean handles(String target) {
		return target != null && TARGET.matcher(target).matches();
	}

	/** Must run before proposal claim: missing evidence leaves PENDING and creates no apply log. */
	public void verify(ChangeProposal proposal, ServingReader reader) {
		var matcher = TARGET.matcher(proposal.getTarget());
		if (!matcher.matches()) throw new IllegalArgumentException("메뉴 삭제 대상이 아닙니다");
		JsonNode oldValue = json(proposal.getOldValue());
		// Existing policy removals remain governed by their explicit policy and serving-side name CAS.
		if (POLICY_EXCLUSION.equals(oldValue.path("reason").asString(null))) return;
		String expectedName = oldValue.path("name").asString(null);
		if (expectedName == null || expectedName.isBlank() || proposal.getSource() != ProvenanceSource.NAVER) {
			throw hold("제안에 NAVER 원천과 메뉴 이름 근거가 없습니다");
		}
		if (reader == null) throw hold("운영 조회가 없어 현재 메뉴와 가게 연결을 확인할 수 없습니다");
		long menuId;
		try { menuId = Long.parseLong(matcher.group(1)); }
		catch (NumberFormatException invalid) { throw hold("메뉴 식별자를 해석할 수 없습니다"); }
		var current = reader.findMenus(proposal.getStoreId()).stream()
			.filter(menu -> Objects.equals(menu.menuId(), menuId)).findFirst()
			.orElseThrow(() -> hold("제안의 메뉴가 현재 이 가게에 속하지 않거나 이미 삭제되었습니다"));
		if (current.userAdded() || !expectedName.equals(current.name())) {
			throw hold("메뉴가 사용자 등록 메뉴이거나 제안 이후 이름이 바뀌었습니다");
		}
		String externalId = evidenceRepository.findExternalId(proposal.getStoreId(), "NAVER")
			.filter(id -> !id.isBlank()).orElseThrow(() -> hold("현재 NAVER 연결이 없습니다"));
		var linked = reader.findStoreByNaverPlaceId(externalId).orElse(null);
		if (linked == null || linked.id() != proposal.getStoreId() || !"ACTIVE".equals(linked.status())) {
			throw hold("현재 운영 가게의 NAVER 연결이 제안 대상과 다릅니다");
		}
		PlaceSnapshot snapshot = proposal.getSnapshotId() == null ? null
			: snapshotRepository.findById(proposal.getSnapshotId()).orElse(null);
		if (snapshot == null || !Objects.equals(snapshot.getStoreId(), proposal.getStoreId())
			|| snapshot.getSource() != SyncSource.NAVER || snapshot.getFetchedAt() == null) {
			throw hold("삭제 제안의 원본 스냅샷을 현재 가게에 연결할 수 없습니다");
		}
		verifyAbsent(json(snapshot.getPayload()), externalId, expectedName, "제안 원본");

		var latest = evidenceRepository.latestCaptureForMenuApproval(proposal.getStoreId(), "NAVER", externalId)
			.orElseThrow(() -> hold("승인 시점에 다시 확인할 수집 원본이 없습니다"));
		if (!"READY".equals(latest.status()) || latest.observedAt() == null || !latest.menuComplete()
			|| !latest.identityVerified() || !Objects.equals(latest.payloadDigest(), latest.evidencePayloadHash())
			|| latest.observedAt().isBefore(snapshot.getFetchedAt())
			|| !evidenceRepository.isLatestVerified(latest.captureId(), proposal.getStoreId(), "NAVER", externalId)) {
			throw hold("최신 원본이 미판정·보류·메뉴 불완전 상태이거나 검증된 관측과 일치하지 않습니다");
		}
		JsonNode latestRaw = json(latest.payload());
		if (!ResponseDigests.sha256(ResponseDigests.canonical(latestRaw, objectMapper)).equals(latest.payloadDigest())) {
			throw hold("최신 원본의 무결성을 확인할 수 없습니다");
		}
		verifyAbsent(latestRaw, externalId, expectedName, "최신 원본");
	}

	private void verifyAbsent(JsonNode raw, String externalId, String expectedName, String label) {
		MenuEvidenceParser.Evidence evidence = evidenceParser.parse(raw, externalId);
		if (!evidence.identityVerified() || !evidence.menuComplete()) {
			throw hold(label + "에서 대상 가게의 완전한 메뉴 목록을 확인할 수 없습니다");
		}
		String expectedKey = ReconcileService.menuKey(expectedName);
		if (expectedKey.isEmpty()) throw hold("메뉴 이름 비교 근거가 없습니다");
		ParsedPlace place;
		try { place = ResponseDiagnostics.parsePreservingInput(placeParser, raw); }
		catch (RuntimeException malformed) { throw hold(label + " 파싱에 실패했습니다"); }
		if (place.pageMissing() || Stream.concat(place.menus().stream(), place.baeminMenus().stream())
			.anyMatch(menu -> expectedKey.equals(ReconcileService.menuKey(menu.name())))) {
			throw hold(label + "에서 메뉴가 다시 보이거나 가게 정보를 확인할 수 없습니다");
		}
	}

	private JsonNode json(String value) {
		try {
			JsonNode parsed = value == null ? null : objectMapper.readTree(value);
			if (parsed == null || !parsed.isObject()) throw hold("삭제 판단에 필요한 JSON 객체가 없습니다");
			return parsed;
		} catch (ProposalException hold) { throw hold; }
		catch (RuntimeException malformed) { throw hold("삭제 판단에 필요한 원본을 해석할 수 없습니다"); }
	}

	private ProposalException hold(String reason) {
		return new ProposalException(ProposalErrorCode.MENU_EVIDENCE_REQUIRED, reason);
	}
}
