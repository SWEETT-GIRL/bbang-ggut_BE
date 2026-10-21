package sweetgirl.croissant.breadlog.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.analysis.BreadPhotoToAnalyze;
import sweetgirl.croissant.breadlog.dto.analysis.RawTagSuggestion;
import sweetgirl.croissant.breadlog.dto.analysis.StoreMenuOption;
import sweetgirl.croissant.external.gemini.client.GeminiClient;

/**
 * 사진 한 장 → 그 가게 메뉴 중 무엇이 찍혔나. 후보 목록을 프롬프트에 넣어 그중에서만 고르게 한다.
 *
 * <p>메뉴 목록 주입이 이 기능의 전부다 — 넣으면 이름 정확도 62.1%, 안 넣으면 7.7% 다(실측 100장).
 * 메뉴 이름 분류와 정반대 결과인데, 사진이 있으면 "후보 중 뭐가 찍혔나"를 고를 수 있기 때문이다.
 *
 * <p>한 장에 한 번만 묻는다. 이름 분류가 쓰는 3회 합의를 그대로 옮겨 봤지만 같은 사진 93장에서
 * 79/126 로 1회와 한 건도 다르지 않았다 — 후보가 열두어 개로 좁아 이미 결정적이라 비용 3배를 낼 이유가 없다.
 */
@Component
@RequiredArgsConstructor
public class BreadPhotoTagSuggester {

	private static final String PROMPT_FILE = "bread-photo-tag-prompt.txt";
	private static final String MENUS_PLACEHOLDER = "{menus}";

	/** 모델이 답하는 좌표의 정규화 기준 — 0~1000 을 0~1 로 접는다 */
	private static final double BOX_SCALE = 1000;
	private static final int BOX_LENGTH = 4;

	private static final String PROMPT_TEMPLATE = loadPromptTemplate();
	private static final Map<String, Object> RESPONSE_SCHEMA = buildResponseSchema();

	private final GeminiClient geminiClient;

	/**
	 * 접기 전의 제안들. 같은 빵이 여러 줄로 오거나 목록 밖 menuId 가 섞여 오므로 걸러 쓴다.
	 *
	 * @param menus 좋아요순 후보. 순서가 곧 프롬프트 순서다
	 */
	public List<RawTagSuggestion> suggest(List<StoreMenuOption> menus, BreadPhotoToAnalyze photo) {
		PhotoAnswer answer = geminiClient.generateStructuredWithImage(
			buildPrompt(menus),
			photo.mimeType(),
			photo.image(),
			RESPONSE_SCHEMA,
			PhotoAnswer.class).value();
		if (answer == null || answer.items() == null) {
			return List.of();
		}
		return answer.items().stream()
			.map(BreadPhotoTagSuggester::toSuggestion)
			.filter(Objects::nonNull)
			.toList();
	}

	private String buildPrompt(List<StoreMenuOption> menus) {
		StringBuilder listing = new StringBuilder();
		for (StoreMenuOption menu : menus) {
			listing.append("  ").append(menu.id()).append(": ").append(menu.name()).append('\n');
		}
		return PROMPT_TEMPLATE.replace(MENUS_PLACEHOLDER, listing.toString().stripTrailing());
	}

	/** [ymin, xmin, ymax, xmax] → 중심점과 넓이. 메뉴를 못 짚었거나 상자가 성하지 않으면 버린다 */
	private static RawTagSuggestion toSuggestion(Item item) {
		List<Integer> box = item.box();
		if (item.menuId() == null || box == null || box.size() != BOX_LENGTH
			|| box.stream().anyMatch(Objects::isNull)) {
			return null;
		}
		double top = normalize(box.get(0));
		double left = normalize(box.get(1));
		double bottom = normalize(box.get(2));
		double right = normalize(box.get(3));
		return new RawTagSuggestion(
			item.menuId(),
			(left + right) / 2,
			(top + bottom) / 2,
			Math.abs(right - left) * Math.abs(bottom - top));
	}

	/** 태그 좌표는 0~1 이어야 한다 — 모델이 범위를 넘겨 답하는 경우가 있어 접어 둔다 */
	private static double normalize(int value) {
		return Math.min(1, Math.max(0, value / BOX_SCALE));
	}

	private static String loadPromptTemplate() {
		try {
			return new ClassPathResource(PROMPT_FILE).getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("사진 태그 프롬프트를 읽을 수 없습니다: " + PROMPT_FILE, e);
		}
	}

	/**
	 * 이름도 함께 답하게 두는 이유 — 정확도를 잰 조건이 이 스키마다. 답한 이름은 쓰지 않고
	 * menuId 로 DB 이름을 찾아 쓴다(목록 밖 이름은 어차피 버린다).
	 */
	private static Map<String, Object> buildResponseSchema() {
		Map<String, Object> item = Map.of(
			"type", "OBJECT",
			"properties", Map.of(
				"menuId", Map.of("type", "INTEGER", "nullable", true),
				"name", Map.of("type", "STRING"),
				"box_2d", Map.of("type", "ARRAY", "items", Map.of("type", "INTEGER"))),
			"required", List.of("name", "box_2d"));
		return Map.of(
			"type", "OBJECT",
			"properties", Map.of("items", Map.of("type", "ARRAY", "items", item)),
			"required", List.of("items"));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PhotoAnswer(List<Item> items) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Item(Long menuId, String name, @JsonProperty("box_2d") List<Integer> box) {
	}
}
