package sweetgirl.croissant.common.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 위경도 두 점 사이의 직선거리(하버사인, m). 코스 안 인접 가게 거리 · 총거리 · 구간 상한(max_hop) 판정에 쓴다.
 * 도보 경로가 아니라 직선이다 — 지도 기능이 생기면 경로 거리로 바꾼다. DB 안에서는 PostGIS ST_Distance 를 쓰고,
 * 이미 읽어 온 좌표끼리 잴 때만 이걸 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GeoDistance {

	private static final double EARTH_RADIUS_M = 6_371_000d;

	public static int metersBetween(double lat1, double lng1, double lat2, double lng2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double haversine = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
		return (int)Math.round(EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine)));
	}
}
