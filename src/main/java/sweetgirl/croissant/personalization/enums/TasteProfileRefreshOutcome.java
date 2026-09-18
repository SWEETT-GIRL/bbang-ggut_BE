package sweetgirl.croissant.personalization.enums;

/**
 * 한 회원의 프로필 재계산 결과. RECOMPUTED 만 version 이 오른다 — 추천이 읽는 값(축·타입·태그·종류)이 바뀐 경우. 유형·카운트만
 * 바뀌면 STATS_ONLY(version 그대로), 아무것도 안 바뀌면 UNCHANGED(쓰지 않음).
 */
public enum TasteProfileRefreshOutcome {
	RECOMPUTED,
	STATS_ONLY,
	UNCHANGED
}
