package sweetgirl.dataops.proposal.policy;

import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.proposal.code.ProposalErrorCode;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.region.dto.LocationEvidence;
import sweetgirl.dataops.region.service.RegionEvidenceFinder;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.dto.StoreCurrentValues;
import tools.jackson.databind.JsonNode;

/**
 * 위치 제안 보류 게이트 (v3 P1-1 → P4-4).
 *
 * 주소·좌표는 지역(region_1st/2nd/3rd)과 한 덩어리다. 주소만 옮기면 가게는 새 자리에 서 있는데 지역은
 * 옛 법정동을 가리키고, 본 앱의 지역 후보 조회는 반경 <b>또는</b> 지역 일치로 찾기 때문에 그 가게가 두 동네에
 * 동시에 나타난다. 그래서 해소 근거가 없는 위치 제안은 승인하지 않고 보류한다(PENDING 유지).
 *
 * 근거의 자리 (P4-4): 제안 JSON 에 실린 지역 필드가 아니라 {@code region_resolution} 이다.
 * <ul>
 *   <li>store.address — 제안의 <b>새 좌표</b>로 역산한 근거가 지금 기준표·투영 버전으로 RESOLVED 여야 한다</li>
 *   <li>store.region — 가게의 <b>지금 운영 좌표</b>로 역산한 근거여야 하고, 제안 지역이 그 근거와 같아야 한다.
 *       다르면 제안 이후 가게가 움직였거나 옛 방식으로 만든 제안이다</li>
 * </ul>
 * 통과하면 근거를 돌려준다 — 승인은 제안 JSON 이 아니라 이 근거의 지역값을 쓴다.
 * legacy 지역 필드가 채워져 있다는 사실만으로는 통과시키지 않는다(스펙 FR-11).
 */
@Component
@RequiredArgsConstructor
public class LocationProposalGate {

	private final RegionEvidenceFinder regionEvidenceFinder;

	/** 이 게이트가 보는 대상인지 — 위치 제안만 점유 전에 검사한다 (보류가 제안을 PENDING 으로 남기도록) */
	public boolean handles(String target) {
		return "store.region".equals(target) || "store.address".equals(target);
	}

	/** @param servingReader 운영 현재 좌표 조회용 — store.region 에서 없으면 보류한다 */
	public LocationEvidence verify(String target, long storeId, JsonNode newValue, ServingReader servingReader) {
		if ("store.address".equals(target)) {
			if (!newValue.path("lon").isNumber() || !newValue.path("lat").isNumber()) {
				throw hold("좌표가 없는 주소 제안 — 지역 근거를 찾을 수 없습니다");
			}
			LocationEvidence evidence = evidenceAt(storeId, newValue.path("lon").asDouble(),
				newValue.path("lat").asDouble(), "새 좌표");
			// 제안이 지역을 싣고 왔다면 근거와 같아야 한다 — 원천 간 충돌은 자동으로 정하지 않는다
			if (newValue.hasNonNull("region_3rd") && !sameRegion(newValue, evidence)) {
				throw hold("제안에 실린 지역이 새 좌표의 법정동 근거와 다릅니다");
			}
			return evidence;
		}
		if ("store.region".equals(target)) {
			if (servingReader == null) {
				throw hold("운영 조회가 설정되지 않아 현재 위치를 확인할 수 없습니다");
			}
			StoreCurrentValues current = servingReader.findStore(storeId).orElse(null);
			if (current == null) {
				throw hold("운영에 없는 가게입니다");
			}
			if (current.longitude() == null || current.latitude() == null) {
				throw hold("운영 좌표가 없어 현재 위치를 확인할 수 없습니다");
			}
			LocationEvidence evidence = evidenceAt(storeId, current.longitude(), current.latitude(), "현재 운영 좌표");
			if (!sameRegion(newValue, evidence)) {
				throw hold(String.format("제안 지역이 현재 좌표의 법정동과 다릅니다 — 근거 %s %s %s",
					evidence.region1st(), evidence.region2nd(), evidence.region3rd()));
			}
			return evidence;
		}
		throw new IllegalArgumentException("위치 제안이 아닙니다: " + target);
	}

	private LocationEvidence evidenceAt(long storeId, double lon, double lat, String where) {
		RegionEvidenceFinder.Lookup lookup = regionEvidenceFinder.lookup(RegionEvidenceFinder.STORE,
			String.valueOf(storeId), lon, lat, where);
		if (!lookup.found()) {
			throw hold(lookup.missingReason());
		}
		return lookup.evidence();
	}

	private boolean sameRegion(JsonNode value, LocationEvidence evidence) {
		return Objects.equals(text(value, "region_1st"), evidence.region1st())
			&& Objects.equals(text(value, "region_2nd"), evidence.region2nd())
			&& Objects.equals(text(value, "region_3rd"), evidence.region3rd());
	}

	private static String text(JsonNode value, String field) {
		JsonNode node = value.path(field);
		return node.isMissingNode() || node.isNull() ? null : node.asString(null);
	}

	private ProposalException hold(String reason) {
		return new ProposalException(ProposalErrorCode.LOCATION_EVIDENCE_REQUIRED, reason);
	}
}
