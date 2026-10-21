package sweetgirl.dataops.candidate.dto.response;

import java.util.List;

/**
 * 등록 배치 결과.
 * dedupResolved = 같은 placeId 의 ACTIVE 가게가 있어 INSERT 없이 귀속한 건.
 * recovered = 이미 store 는 있었고 빠진 후속(sync_state·외부 ID·초기 수집 예약)만 보완한 건.
 * held = 검증된 위치 근거가 없거나 DELETED 가게라 진행하지 않은 건 — MATCHED 로 남아 근거가 갖춰지면 다시 시도한다.
 * unknown = 운영 쓰기 결과를 확인하지 못한 건 — 확인 전에는 다시 쓰지 않는다.
 */
public record CandidateRegisterResult(
	int requested,
	int registered,
	int dedupResolved,
	int recovered,
	int held,
	int unknown,
	int skipped,
	int failed,
	List<String> failedMgmtNos) {
}
