package sweetgirl.croissant.recommendation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.recommendation.enums.RecommendationMode;

class RecommendationTest {

	private static Map<String, Object> resultWithNullSkipped() {
		Map<String, Object> result = new HashMap<>();
		result.put("title", "성수 담백 산책");
		result.put("stores", List.of(Map.of("storeId", 1042, "order", 1, "reason", "담백·바삭 취향에 소금빵")));
		result.put("skipped", null);
		return result;
	}

	@Test
	@DisplayName("상권 추천은 provider·model·promptVersion·fallback 을 그대로 남긴다")
	void forArea() {
		Recommendation recommendation = Recommendation.forArea(
			7L, 10L, 5L, (short)1, 3, "gemini", "gemini-2.5-flash", "p1", true, resultWithNullSkipped());

		assertThat(recommendation.getMode()).isEqualTo(RecommendationMode.AREA);
		assertThat(recommendation.isAnchor()).isFalse();
		assertThat(recommendation.getCourseAreaId()).isEqualTo(5L);
		assertThat(recommendation.getProfileVersion()).isEqualTo(3);
		assertThat(recommendation.getPromptVersion()).isEqualTo("p1");
		assertThat(recommendation.isFallback()).isTrue();
		assertThat(recommendation.getResult()).containsEntry("skipped", null);
		assertThat(recommendation.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("앵커 추천도 이유는 LLM 이 쓰므로 provider·model·promptVersion 을 남긴다")
	void forAnchor() {
		Recommendation recommendation = Recommendation.forAnchor(
			7L, 10L, 1042L, (short)1, 3, "gemini", "gemini-2.5-flash", "p1", false, resultWithNullSkipped());

		assertThat(recommendation.getMode()).isEqualTo(RecommendationMode.ANCHOR);
		assertThat(recommendation.isAnchor()).isTrue();
		assertThat(recommendation.getAnchorStoreId()).isEqualTo(1042L);
		assertThat(recommendation.getCourseAreaId()).isNull();
		assertThat(recommendation.getProvider()).isEqualTo("gemini");
		assertThat(recommendation.getModel()).isEqualTo("gemini-2.5-flash");
		assertThat(recommendation.getPromptVersion()).isEqualTo("p1");
		assertThat(recommendation.isFallback()).isFalse();
	}

	@Test
	@DisplayName("앵커 이유를 LLM 이 못 쓰면 provider 는 rule, fallback 은 true 로 남는다")
	void forAnchor_fallback() {
		Recommendation recommendation = Recommendation.forAnchor(
			7L, 10L, 1042L, (short)1, 3, Recommendation.RULE_PROVIDER, null, null, true, resultWithNullSkipped());

		assertThat(recommendation.getProvider()).isEqualTo(Recommendation.RULE_PROVIDER);
		assertThat(recommendation.getModel()).isNull();
		assertThat(recommendation.isFallback()).isTrue();
	}

	@Test
	@DisplayName("결과는 복사해 두므로 원본을 바꿔도 영향이 없다")
	void result_isCopied() {
		Map<String, Object> mutable = resultWithNullSkipped();
		Recommendation recommendation = Recommendation.forAnchor(
			7L, 10L, 1042L, null, 1, Recommendation.RULE_PROVIDER, null, null, true, mutable);

		mutable.clear();

		assertThat(recommendation.getResult()).containsKey("title");
	}
}
