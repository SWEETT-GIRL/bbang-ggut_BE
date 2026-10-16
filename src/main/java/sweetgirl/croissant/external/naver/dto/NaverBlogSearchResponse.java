package sweetgirl.croissant.external.naver.dto;

import java.util.List;

public record NaverBlogSearchResponse(
		List<Item> items) {

	public record Item(
			String title,
			String link,
			String description,
			String bloggername,
			String postdate) {
	}
}
