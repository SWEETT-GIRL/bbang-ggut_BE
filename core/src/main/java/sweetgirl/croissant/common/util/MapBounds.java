package sweetgirl.croissant.common.util;

import java.util.Optional;

/**
 * 지도에 보이는 사각형 영역(남서·북동 모서리). 지도 목록이 "보이는 영역 안의 가게"를 한 번에 받을 때 쓴다.
 *
 * <p>날짜변경선을 넘는 영역은 받지 않는다(한국 지도라 없다) — 서경이 동경보다 크면 400.
 * 범위 밖 좌표는 PostGIS 가 500 을 내므로 여기서 400 으로 먼저 거른다.
 */
public record MapBounds(double swLat, double swLng, double neLat, double neLng) {

	public MapBounds {
		if (!isLatitude(swLat) || !isLatitude(neLat) || !isLongitude(swLng) || !isLongitude(neLng)) {
			throw new IllegalArgumentException("위도는 -90~90, 경도는 -180~180 이어야 합니다");
		}
		if (swLat > neLat || swLng > neLng) {
			throw new IllegalArgumentException("남서(sw) 모서리가 북동(ne) 모서리보다 작아야 합니다");
		}
	}

	/**
	 * 네 값이 모두 있으면 영역, 모두 없으면 빈 값. 일부만 오면 400 — 영역 없이 찾는 것으로 조용히 넘어가면
	 * 앱은 영역으로 거른 줄 알고 전국 결과를 받는다.
	 */
	public static Optional<MapBounds> ofNullable(Double swLat, Double swLng, Double neLat, Double neLng) {
		if (swLat == null && swLng == null && neLat == null && neLng == null) {
			return Optional.empty();
		}
		if (swLat == null || swLng == null || neLat == null || neLng == null) {
			throw new IllegalArgumentException("swLat·swLng·neLat·neLng 는 함께 주어야 합니다");
		}
		return Optional.of(new MapBounds(swLat, swLng, neLat, neLng));
	}

	public double centerLat() {
		return (swLat + neLat) / 2;
	}

	public double centerLng() {
		return (swLng + neLng) / 2;
	}

	private static boolean isLatitude(double value) {
		return value >= -90 && value <= 90;
	}

	private static boolean isLongitude(double value) {
		return value >= -180 && value <= 180;
	}
}
