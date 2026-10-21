package sweetgirl.dataops.candidate.dto.response;

import sweetgirl.dataops.region.dto.LocationEvidence;

/**
 * 등록 미리보기 한 항목 — 등록 배치와 같은 판정을 쓰지 않고 보여 준다.
 *
 * @param plan INSERT / DEDUP / RECOVER / HOLD_* / UNKNOWN / SKIP / ERROR
 * @param existingStoreId DEDUP·RECOVER·HOLD_DELETED_STORE 일 때 운영에서 찾은 가게
 * @param evidence INSERT 일 때 쓸 지역의 근거
 * @param probeRowId probe 요청 시 운영 INSERT 후 롤백한 행의 id — 실제 등록 id 가 아니다
 * @param probeError probe 에서 운영 제약에 걸린 사유
 */
public record CandidateRegisterPreview(
	String mgmtNo,
	String name,
	String address,
	String plan,
	Long existingStoreId,
	String reason,
	LocationEvidence evidence,
	Long probeRowId,
	String probeError) {
}
