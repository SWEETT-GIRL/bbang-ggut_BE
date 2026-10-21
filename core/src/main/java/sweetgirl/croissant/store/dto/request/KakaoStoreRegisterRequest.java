package sweetgirl.croissant.store.dto.request;

public record KakaoStoreRegisterRequest(
		String kakaoPlaceId,
		String name,
		String address,
		String addressName,
		String phone,
		String x,
		String y) {
}
