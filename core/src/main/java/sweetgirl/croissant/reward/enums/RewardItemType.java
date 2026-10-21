package sweetgirl.croissant.reward.enums;

/** 응답 {@code rewards[]} 한 칸의 종류. 앱은 배열 순서대로 이 종류에 맞는 연출을 튼다. */
public enum RewardItemType {
	/** 빵 칸(기록 수)이 올랐다. */
	BREAD,
	/** 부스러기를 받았다. 상한에 걸렸으면 {@code capped: true, amount: 0} 이다. */
	CRUMBS,
	/** 레벨이 올랐다. */
	LEVEL_UP,
	/** 빵투어 단계(완주 · 사진 완주). */
	TOUR_STEP
}
