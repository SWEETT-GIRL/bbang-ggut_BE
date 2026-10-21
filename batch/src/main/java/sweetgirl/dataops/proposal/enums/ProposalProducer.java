package sweetgirl.dataops.proposal.enums;

/**
 * 제안을 만든 판정 주체 (v3 P1-2) — 재판정 범위의 단위다.
 *
 * 재판정은 자기 producer 가 만든 제안만 정리한다. 다른 producer 의 제안을 지우면
 * 네이버 스냅샷 재판정 한 번이 공공데이터 폐업 제안이나 매칭 제안을 함께 날린다.
 * {@link #LEGACY_UNKNOWN} 은 만든 주체를 증거로 특정할 수 없는 과거 행의 자리다 —
 * 보호를 주장하지 않으므로 알려진 producer 가 대체할 수 있지만, 범위 정리의 대상은 아니다.
 */
public enum ProposalProducer {
	/** ReconcileService — 저장된 네이버 스냅샷과 운영 값의 비교 (메뉴 정책 재판정 포함) */
	NAVER_SNAPSHOT_RECONCILE,
	/** 공공데이터 폐업·중복 정리 (localdata_status_delta / localdata_enqueue / localdata_dup_cleanup) */
	LOCALDATA_STATUS,
	/** region_recompute.py — 좌표 역산(카카오 법정동)으로 만든 지역 제안 */
	REGION_RECOMPUTE,
	/** store_category_propose.py — 네이버 카테고리 → BE StoreCategory 코드 */
	STORE_CATEGORY_PROPOSE,
	/** 눈검사 통과 (storeId, naverPlaceId) 적재 — MatchingCommandService */
	NAVER_MATCHING,
	/**
	 * 사람이 원천 밖의 사실로 판단해 만든 제안 (예: 이전 후 영업 준비중인 가게의 임시 비활성, 2026-09-26).
	 * 어떤 재판정도 이 producer 의 제안을 정리하지 않는다 — 판단의 근거가 재판정 입력에 없기 때문이다
	 */
	ADMIN_REVIEW,
	/** 만든 주체를 증거로 특정할 수 없는 과거 행 — 지어내지 않는다 */
	LEGACY_UNKNOWN
}
