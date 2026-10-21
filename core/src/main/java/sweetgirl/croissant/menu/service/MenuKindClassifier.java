package sweetgirl.croissant.menu.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.gemini.client.GeminiClient;
import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.gemini.exception.GeminiException;
import sweetgirl.croissant.menu.config.MenuClassificationProperties;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.enums.MenuKind;

/**
 * 메뉴 이름 → MenuKind. Gemini 에 같은 질문을 여러 번 던져 답이 일치할 때만 확정한다.
 *
 * <p>왜 confidence 가 아니라 일치인가 — 실측(200건)에서 confidence 0.99 를 달고도 실행마다 답이 갈리는
 * 이름이 있었고, 가게 컨텍스트를 주면 틀린 답에도 0.8 이 붙었다. 모델의 자기 점수는 못 믿는다.
 * 대신 temperature 0 으로 3회 물어 하나라도 다르면 "모른다"로 친다 — 3회 일치가 92%, 그중 오류 1.1%.
 *
 * <p>DB 트랜잭션 밖에서 불려야 한다 — 100건 3회면 수십 초, 429 대기까지 하면 더 길다.
 */
@Slf4j
@Component
public class MenuKindClassifier {

	private static final String PROMPT_FILE = "menu-classification-prompt.txt";

	private final GeminiClient geminiClient;
	private final GeminiProperties geminiProperties;
	private final MenuClassificationProperties properties;
	private final String promptTemplate;
	private final Map<String, Object> responseSchema;

	public MenuKindClassifier(
			GeminiClient geminiClient,
			GeminiProperties geminiProperties,
			MenuClassificationProperties properties) {
		this.geminiClient = geminiClient;
		this.geminiProperties = geminiProperties;
		this.properties = properties;
		this.promptTemplate = loadPromptTemplate();
		this.responseSchema = buildResponseSchema();
	}

	/**
	 * @param nameByKey 정규화 키 → 프롬프트에 넣을 대표 원본 이름. 순서 유지되는 Map 이어야 한다
	 * @return 입력 순서대로의 판정
	 */
	public List<MenuKindVerdict> classify(Map<String, String> nameByKey) {
		if (nameByKey.isEmpty()) {
			return List.of();
		}
		List<String> keys = new ArrayList<>(nameByKey.keySet());
		String prompt = buildPrompt(keys.stream().map(nameByKey::get).toList());

		List<Map<Integer, Item>> runs = new ArrayList<>();
		for (int run = 0; run < properties.getRuns(); run++) {
			runs.add(indexAnswers(askWithRetry(prompt)));
		}

		List<MenuKindVerdict> verdicts = new ArrayList<>(keys.size());
		for (int index = 0; index < keys.size(); index++) {
			int position = index;
			List<Item> answers = runs.stream().map(run -> run.get(position)).toList();
			verdicts.add(decide(keys.get(index), answers));
		}
		return verdicts;
	}

	public String modelName() {
		return geminiProperties.getModel();
	}

	/**
	 * 회원 메뉴 등록 화면용 즉답 — 한 번만 묻고, 재시도·대기 없이 상한 안에 못 받으면 빈 값.
	 * 확정이 아니다(3회 일치가 아니므로). 화면에 칩으로 보여 주고, 저장은 스케줄러가 3회로 한다.
	 * 비-빵이거나 '빵'·'기타'로만 답하면 제안하지 않는다.
	 */
	public Optional<MenuKind> suggest(String name) {
		String prompt = buildPrompt(List.of(name));
		try {
			// 사용자 대기 경로라 클라이언트의 60초 타임아웃을 그대로 두지 않는다. 넘기면 요청은 버리고 빈 값
			BatchAnswer answer = CompletableFuture
				.supplyAsync(() -> geminiClient.generateStructured(prompt, responseSchema, BatchAnswer.class).value())
				.orTimeout(properties.getSuggestTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.join();
			Item item = indexAnswers(answer).get(0);
			if (item == null || !item.isBakeryItem()) {
				return Optional.empty();
			}
			MenuKind kind = parseKind(item.kind());
			return kind == null || MenuKindVerdict.isFallbackKind(kind) ? Optional.empty() : Optional.of(kind);
		} catch (CompletionException | CancellationException failed) {
			Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
			log.info("메뉴 kind 즉답 실패 — 제안 없이 진행: {}", cause.toString());
			return Optional.empty();
		}
	}

	/** 한 이름에 대한 N회 답을 하나의 판정으로 */
	private MenuKindVerdict decide(String key, List<Item> answers) {
		List<Item> present = answers.stream().filter(Objects::nonNull).toList();
		double confidence = present.stream().mapToDouble(Item::confidence).average().orElse(0);
		// 응답이 하나라도 빠졌으면 일치를 말할 수 없다
		if (present.size() < properties.getRuns()) {
			return MenuKindVerdict.undecided(key, confidence);
		}
		long bakeryVotes = present.stream().filter(Item::isBakeryItem).count();
		if (bakeryVotes * 2 <= present.size()) {
			return MenuKindVerdict.nonBakery(key, confidence);
		}
		Set<MenuKind> kinds = present.stream().map(item -> parseKind(item.kind())).collect(Collectors.toSet());
		if (kinds.size() != 1 || kinds.contains(null)) {
			return MenuKindVerdict.undecided(key, confidence);
		}
		MenuKind kind = kinds.iterator().next();
		// '빵'·'기타'로 세 번 합의한 건 "모르겠다"를 세 번 말한 것 — 카테고리를 주지 않는다
		if (MenuKindVerdict.isFallbackKind(kind)) {
			return MenuKindVerdict.undecided(key, confidence);
		}
		return MenuKindVerdict.agreed(key, kind, confidence);
	}

	private BatchAnswer askWithRetry(String prompt) {
		for (int attempt = 0;; attempt++) {
			try {
				return geminiClient.generateStructured(prompt, responseSchema, BatchAnswer.class).value();
			} catch (GeminiException exception) {
				if (!exception.isRetryable() || attempt >= properties.getMaxRetries()) {
					throw exception;
				}
				log.info("Gemini {} — {}초 뒤 재시도 ({}/{})", exception.getGeminiErrorCode(),
					properties.getRetryBackoff().toSeconds(), attempt + 1, properties.getMaxRetries());
				pause();
			}
		}
	}

	private void pause() {
		try {
			Thread.sleep(properties.getRetryBackoff().toMillis());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("메뉴 분류 재시도 대기 중 인터럽트", interrupted);
		}
	}

	private Map<Integer, Item> indexAnswers(BatchAnswer answer) {
		Map<Integer, Item> byIndex = new HashMap<>();
		if (answer != null && answer.items() != null) {
			answer.items().forEach(item -> byIndex.put(item.index(), item));
		}
		return byIndex;
	}

	private MenuKind parseKind(String kind) {
		if (kind == null) {
			return null;
		}
		try {
			return MenuKind.valueOf(kind.strip());
		} catch (IllegalArgumentException outsideEnum) {
			// 스키마에 enum 을 걸어 두어 여기 올 일은 없지만, 오면 "모름"으로
			return null;
		}
	}

	private String buildPrompt(List<String> names) {
		String kinds = Arrays.stream(MenuKind.values()).map(Enum::name).collect(Collectors.joining(", "));
		StringBuilder items = new StringBuilder();
		for (int index = 0; index < names.size(); index++) {
			items.append(index).append(". ").append(names.get(index)).append('\n');
		}
		return promptTemplate.replace("{kinds}", kinds).replace("{items}", items.toString().stripTrailing());
	}

	private static String loadPromptTemplate() {
		try {
			return new ClassPathResource(PROMPT_FILE).getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("메뉴 분류 프롬프트를 읽을 수 없습니다: " + PROMPT_FILE, e);
		}
	}

	/** kind 에 MenuKind 전체를 enum 으로 걸어 목록 밖 값을 API 단에서 막는다 */
	private static Map<String, Object> buildResponseSchema() {
		List<String> kindNames = Arrays.stream(MenuKind.values()).map(Enum::name).toList();
		Map<String, Object> item = Map.of(
			"type", "OBJECT",
			"properties", Map.of(
				"index", Map.of("type", "INTEGER"),
				"kind", Map.of("type", "STRING", "enum", kindNames),
				"isBakeryItem", Map.of("type", "BOOLEAN"),
				"confidence", Map.of("type", "NUMBER")),
			"required", List.of("index", "kind", "isBakeryItem", "confidence"));
		return Map.of(
			"type", "OBJECT",
			"properties", Map.of("items", Map.of("type", "ARRAY", "items", item)),
			"required", List.of("items"));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record BatchAnswer(List<Item> items) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Item(int index, String kind, boolean isBakeryItem, double confidence) {
	}
}
