package sweetgirl.croissant.external.naver.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NaverBlogPostTest {

	@Test
	@DisplayName("검색어 강조 태그와 HTML 엔티티를 걷어낸다")
	void from_cleansHtml() {
		NaverBlogSearchResponse.Item item = new NaverBlogSearchResponse.Item(
			"<b>런베뮤</b> 안국 웨이팅 &amp; 후기", "https://blog.naver.com/a/1",
			"&quot;런던베이글뮤지엄&quot; 줄여서 <b>런베뮤</b>", "블로거", "20261001");

		NaverBlogPost post = NaverBlogPost.from(item);

		assertThat(post.title()).isEqualTo("런베뮤 안국 웨이팅 & 후기");
		assertThat(post.description()).isEqualTo("\"런던베이글뮤지엄\" 줄여서 런베뮤");
		assertThat(post.text()).contains("런베뮤 안국").contains("런던베이글뮤지엄");
	}

	@Test
	@DisplayName("제목·요약이 없으면 빈 문자열로 둔다")
	void from_nullFields() {
		NaverBlogPost post = NaverBlogPost.from(new NaverBlogSearchResponse.Item(null, "link", null, null, null));

		assertThat(post.title()).isEmpty();
		assertThat(post.description()).isEmpty();
	}
}
