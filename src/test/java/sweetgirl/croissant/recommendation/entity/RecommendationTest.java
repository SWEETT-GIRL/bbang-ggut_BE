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
		assertThat(recommendation.getSessionId()).isNull(); // 세션 id 없이 만든 옛 모양
		assertThat(Recommendation.forArea(7L, 10L, 5L, (short)1, 3, "rule", null, null, false,
			resultWithNullSkipped(), "1757894400123").getSessionId()).isEqualTo("1757894400123");
		assertThat(recommendation.isFallback()).isTrue();
		assertThat(recommendation.getResult()).containsEntry("skipped", null);
		assertThat(recommendation.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("앵커 추천은 룰만이라 provider 가 rule 로 고정이고 model·promptVersion·fallback 이 없다")
	void forAnchor() {
		Recommendation recommendation = Recommendation.forAnchor(7L, 10L, 1042L, (short)1, 3, resultWithNullSkipped());

		assertThat(recommendation.getMode()).isEqualTo(RecommendationMode.ANCHOR);
		assertThat(recommendation.isAnchor()).isTrue();
		assertThat(recommendation.getAnchorStoreId()).isEqualTo(1042L);
		assertThat(recommendation.getCourseAreaId()).isNull();
		assertThat(recommendation.getProvider()).isEqualTo(Recommendation.RULE_PROVIDER);
		assertThat(recommendation.getModel()).isNull();
		assertThat(recommendation.getPromptVersion()).isNull();
		assertThat(recommendation.isFallback()).isFalse();
	}

	@Test
	@DisplayName("결과는 복사해 두므로 원본을 바꿔도 영향이 없다")
	void result_isCopied() {
		Map<String, Object> mutable = resultWithNullSkipped();
		Recommendation recommendation = Recommendation.forAnchor(7L, 10L, 1042L, null, 1, mutable);

		mutable.clear();

		assertThat(recommendation.getResult()).containsKey("title");
	}

	@Test
	@DisplayName("결과에 있던 가게만 있다고 답한다 — stores 가 없거나 모양이 다르면 없다")
	void containsStore() {
		Recommendation recommendation = Recommendation.forAnchor(7L, 10L, 1042L, (short)1, 1, resultWithNullSkipped());
		Recommendation noStores = Recommendation.forAnchor(7L, 10L, 1042L, (short)1, 1, Map.of("title", "빈 결과"));

		assertThat(recommendation.containsStore(1042L)).isTrue();
		assertThat(recommendation.containsStore(7L)).isFalse();
		assertThat(noStores.containsStore(1042L)).isFalse();
	}
}
