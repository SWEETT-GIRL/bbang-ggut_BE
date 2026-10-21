package sweetgirl.dataops.proposal.dto.response;

import sweetgirl.dataops.region.dto.LocationEvidence;

/**
 * 위치 제안 게이트 미리보기 (v3 P4-4) — 승인하면 어떤 지역을 쓰게 되는지, 보류라면 왜인지.
 * 점유·운영 쓰기를 하지 않는다. 승인 직전의 게이트와 같은 판정이다.
 *
 * @param passed 지금 승인하면 게이트를 통과하는가
 * @param holdReason 보류 사유 (통과면 null)
 * @param evidence 통과 시 쓰게 될 지역과 근거 (보류면 null)
 */
public record LocationGateResponse(Long proposalId, String target, boolean passed, String holdReason,
		LocationEvidence evidence) {
}
