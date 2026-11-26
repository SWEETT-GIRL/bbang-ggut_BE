package sweetgirl.dataops.reconcile.enums;

/**
 * 필드 값의 출처 — 병합 정책의 우선순위 입력.
 * 우선순위: ADMIN > USER > NAVER > LOCALDATA > 미기록(최초 벌크).
 */
public enum ProvenanceSource {
	ADMIN,
	USER,
	NAVER,
	LOCALDATA,
	/** 좌표 역산(카카오 coord2regioncode) — 가게 지역 region_1st/2nd/3rd 의 출처 (이슈 #5, 2026-09-08) */
	KAKAO,
	/** 네이버 응답에 포함된 배민 메뉴. 배달 가격의 출처를 일반 메뉴와 구분한다. */
	BAEMIN
}
