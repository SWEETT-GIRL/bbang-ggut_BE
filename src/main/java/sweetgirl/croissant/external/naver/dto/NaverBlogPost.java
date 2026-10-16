package sweetgirl.croissant.external.naver.dto;

/**
 * 블로그 검색 결과 한 건. 네이버가 검색어 강조용으로 넣는 {@code <b>} 태그와 HTML 엔티티를 걷어낸 값이다.
 *
 * @param description 본문 요약(검색어 주변 한두 문장)
 */
public record NaverBlogPost(
		String title,
		String description,
		String link) {

	public static NaverBlogPost from(NaverBlogSearchResponse.Item item) {
		return new NaverBlogPost(clean(item.title()), clean(item.description()), item.link());
	}

	/** 근거 검증은 제목과 요약을 이어 붙인 문자열에서 이름을 찾는다. */
	public String text() {
		return title + " " + description;
	}

	private static String clean(String html) {
		if (html == null) {
			return "";
		}
		return html.replaceAll("<[^>]*>", "")
			.replace("&amp;", "&")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&#39;", "'")
			.trim();
	}
}
