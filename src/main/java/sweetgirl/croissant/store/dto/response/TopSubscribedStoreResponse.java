package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;

public record TopSubscribedStoreResponse(
		Long id,
		String name,
		/**
		 * 항상 0. 구버전 앱이 이 값으로 다시 정렬하므로 모두 같게 두어 서버의 인기도 순서를 지킨다(안정 정렬).
		 * 재정렬을 뺀 앱 빌드가 충분히 퍼지면 제거한다.
		 */
		@Deprecated long subscribeCount) {
	public static TopSubscribedStoreResponse from(TopSubscribedStoreProjection projection) {
		return new TopSubscribedStoreResponse(projection.getId(), projection.getName(), 0L);
	}
}
