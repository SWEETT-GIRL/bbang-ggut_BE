package sweetgirl.croissant.store.dto.response;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sweetgirl.croissant.external.naver.dto.NaverLocalSearchResponse;

public record NaverPlaceSearchResult(
		String naverPlaceId,
		String name,
		String category,
		String phone,
		String address,
		String roadAddress,
		Double latitude,
		Double longitude,
		String link) {

	private static final Pattern PLACE_ID_PATTERN = Pattern.compile("(?:code=|/place/)(\\d+)");
	private static final double NAVER_COORDINATE_SCALE = 10_000_000D;

	public static NaverPlaceSearchResult from(NaverLocalSearchResponse.Item item) {
		return new NaverPlaceSearchResult(
			extractPlaceId(item.link()),
			cleanTitle(item.title()),
			item.category(),
			item.telephone(),
			firstNonBlank(item.roadAddress(), item.address()),
			item.roadAddress(),
			toLatitude(item.mapy()),
			toLongitude(item.mapx()),
			item.link());
	}

	private static String extractPlaceId(String link) {
		if (link == null || link.isBlank()) {
			return null;
		}
		Matcher matcher = PLACE_ID_PATTERN.matcher(link);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String cleanTitle(String title) {
		if (title == null) {
			return null;
		}
		return title.replaceAll("<[^>]*>", "")
			.replace("&amp;", "&")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.trim();
	}

	private static Double toLatitude(String mapy) {
		return toCoordinate(mapy);
	}

	private static Double toLongitude(String mapx) {
		return toCoordinate(mapx);
	}

	private static Double toCoordinate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Double.parseDouble(value) / NAVER_COORDINATE_SCALE;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String firstNonBlank(String first, String second) {
		return first != null && !first.isBlank() ? first : second;
	}
}
