package sweetgirl.croissant.reward.enums;

/** 홈의 할 일 카드 종류. 한 번에 한 장만 보이고, 위에 적힌 것일수록 먼저다. */
public enum HomeCardType {
	/** 도착했는데 룰렛을 안 돌린 행성이 있다. */
	ROULETTE,
	/** 오늘이 내 빵투어 날이다. */
	TOUR_TODAY,
	/** 안 연 빵댕이 편지가 있다. */
	LETTER,
	/** 오늘 밥을 안 줬다. 더는 고르지 않는다 — 옛 앱이 이 값을 읽으므로 남겨 둔다. */
	FEED,
	/** 7 일 안에 빵투어가 있다. */
	TOUR_SOON
}
