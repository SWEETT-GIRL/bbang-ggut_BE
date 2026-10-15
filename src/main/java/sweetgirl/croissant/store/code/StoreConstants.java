package sweetgirl.croissant.store.code;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class StoreConstants {

	public static final int TOP_MENU_LIMIT = 5;
	public static final double SEARCH_NEARBY_RADIUS = 1000.0;
	/** 근처 빵집 반경 상한(m). 시·군 하나를 덮는 정도 — 더 넓히면 거리순 정렬이 너무 많은 가게를 훑는다. */
	public static final double MAX_SEARCH_NEARBY_RADIUS = 20_000.0;
	public static final int TOP_SUBSCRIBED_STORE_LIMIT = 10;
}
