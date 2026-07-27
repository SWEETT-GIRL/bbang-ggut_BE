package sweetgirl.croissant.store.dto.request;

/**
 * 네이버 검색 결과에서 사용자가 고른 가게를 바로 등록할 때 쓰는 요청.
 * naverPlaceId는 받되 store에 저장하지 않는다 — 저장하면 네이버 보강 큐에서
 * '매칭 완료'로 빠져 사진이 채워지지 않는다.
 */
public record NaverStoreRegisterRequest(
		String naverPlaceId,
		String name,
		String address,
		String roadAddress,
		String phone,
		Double latitude,
		Double longitude) {
}
