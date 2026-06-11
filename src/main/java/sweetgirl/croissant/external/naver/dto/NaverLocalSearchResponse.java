package sweetgirl.croissant.external.naver.dto;

import java.util.List;

public record NaverLocalSearchResponse(
		List<Item> items) {

	public record Item(
			String title,
			String link,
			String category,
			String telephone,
			String address,
			String roadAddress,
			String mapx,
			String mapy) {
	}
}
