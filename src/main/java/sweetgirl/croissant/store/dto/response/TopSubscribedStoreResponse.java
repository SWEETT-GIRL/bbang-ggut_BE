package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;

/**
 * 인기 빵집 한 줄.
 *
 * <p>region·address·thumbnailImageKey 는 1.5.x 앱이 가게 고르기 화면에서 그대로 읽는다. #818 이 셋을 빼자
 * 그 앱의 {@code address || toDong(region)} 이 {@code undefined.trim()} 이 되어 앱이 꺼졌다(2026-10-02).
 * 앱 쪽 널 가드는 1.6.0 에야 실리므로, 설치된 앱이 다 바뀔 때까지 서버가 셋을 계속 내려야 한다.
 */
public record TopSubscribedStoreResponse(
		Long id,
		String name,
		/** "서울 성동구" 꼴. 둘 다 없으면 빈 문자열 — null 을 내리면 1.5.x 앱의 toDong 이 또 터진다 */
		String region,
		/** 전체 주소 — 목록에서 "여기가 맞나?"를 가르는 줄. region(시·도 구)만으론 부족했다 */
		String address,
		String thumbnailImageKey,
		/**
		 * 항상 0. 구버전 앱이 이 값으로 다시 정렬하므로 모두 같게 두어 서버의 인기도 순서를 지킨다(안정 정렬).
		 * 재정렬을 뺀 앱 빌드가 충분히 퍼지면 제거한다.
		 */
		@Deprecated long subscribeCount) {
	public static TopSubscribedStoreResponse of(TopSubscribedStoreProjection projection, String thumbnailImageKey) {
		return new TopSubscribedStoreResponse(
			projection.getId(),
			projection.getName(),
			joinRegion(projection.getRegion1st(), projection.getRegion2nd()),
			projection.getAddress(),
			thumbnailImageKey,
			0L);
	}

	private static String joinRegion(String region1st, String region2nd) {
		if (region1st == null && region2nd == null) {
			return "";
		}
		if (region1st == null) {
			return region2nd;
		}
		if (region2nd == null) {
			return region1st;
		}
		return region1st + " " + region2nd;
	}
}
