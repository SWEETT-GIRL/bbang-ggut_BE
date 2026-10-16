package sweetgirl.croissant.searchmiss.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmModelRef;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.naver.client.NaverBlogSearchClient;
import sweetgirl.croissant.external.naver.dto.NaverBlogPost;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

/**
 * 실패 검색어 판정에서 AI 가 하는 유일한 일 — 블로그 글을 읽고 "런베뮤 → 런던베이글뮤지엄"처럼 검색어가 가리키는 대상을 해석한다.
 *
 * <p>"런베뮤"는 네이버 지역검색에 장소가 안 나오고, 블로그 글을 읽어야 무엇인지 보인다. 비정형 글에서 관계를 뽑는 일이라 규칙으로는
 * 못 한다. 대신 LLM 답을 그대로 믿지 않는다 — 정식 이름이 근거로 든 글에 <b>글자 그대로</b> 있는지, 검색어와 이름이 줄임말·포함·오타
 * 관계인지를 코드로 확인한다. 지어낸 이름은 여기서 걸러진다.
 */
@Component
public class SearchMissKeywordInterpreter {

	private static final String PROMPT_FILE = "search-miss-interpret-prompt.txt";
	private static final double TEMPERATURE = 0.0;
	static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
		"type", "object",
		"properties", Map.of(
			"refersTo", Map.of("type", "string", "enum", List.of("STORE", "MENU", "REGION", "UNKNOWN")),
			"officialName", Map.of("type", "string"),
			"evidence", Map.of("type", "array", "items", Map.of("type", "integer"))),
		"required", List.of("refersTo", "officialName", "evidence"),
		"additionalProperties", false);

	private final LlmClientRegistry llmClientRegistry;
	private final NaverBlogSearchClient naverBlogSearchClient;
	private final SearchMissTriageProperties properties;
	private final LlmModelRef model;
	private final String promptTemplate;

	public SearchMissKeywordInterpreter(LlmClientRegistry llmClientRegistry,
			NaverBlogSearchClient naverBlogSearchClient, SearchMissTriageProperties properties) {
		this.llmClientRegistry = llmClientRegistry;
		this.naverBlogSearchClient = naverBlogSearchClient;
		this.properties = properties;
		// 설정 잘못은 첫 판정이 아니라 기동에서 드러나야 한다
		this.model = LlmModelRef.parse(properties.getModel());
		this.promptTemplate = loadPrompt();
	}

	public KeywordInterpretation interpret(String keyword) {
		List<NaverBlogPost> posts = naverBlogSearchClient.search(keyword, properties.getBlogDisplay());
		if (posts.isEmpty()) {
			return KeywordInterpretation.unresolved("블로그 글 없음");
		}
		LlmRequest request = new LlmRequest(model.provider(), model.model(), buildPrompt(keyword, posts),
			RESPONSE_SCHEMA, TEMPERATURE, properties.getMaxOutputTokens());
		InterpretAnswer answer = llmClientRegistry.generateStructured(request, InterpretAnswer.class).value();
		return verify(keyword, posts, answer);
	}

	KeywordInterpretation verify(String keyword, List<NaverBlogPost> posts, InterpretAnswer answer) {
		if (answer == null || answer.refersTo() == null) {
			return KeywordInterpretation.unresolved("LLM 응답 없음");
		}
		switch (answer.refersTo()) {
			case "MENU", "REGION" -> {
				return KeywordInterpretation.notAStore("블로그 해석: " + answer.refersTo());
			}
			case "STORE" -> {
				return verifyStore(keyword, posts, answer);
			}
			default -> {
				return KeywordInterpretation.unresolved("블로그로 판단 불가");
			}
		}
	}

	private KeywordInterpretation verifyStore(String keyword, List<NaverBlogPost> posts, InterpretAnswer answer) {
		String officialName = answer.officialName() == null ? "" : answer.officialName().trim();
		if (officialName.isEmpty()) {
			return KeywordInterpretation.unresolved("가게라는데 이름이 없음");
		}
		List<Integer> cited = answer.evidence() == null ? List.of() : answer.evidence();
		// 글 번호는 1부터. 범위 밖 번호와 중복은 버린다 — 같은 글을 두 번 세면 근거 수가 부풀려진다
		List<NaverBlogPost> supporting = cited.stream()
			.filter(number -> number != null && number >= 1 && number <= posts.size())
			.distinct()
			.map(number -> posts.get(number - 1))
			.filter(post -> SearchMissTextMatcher.appearsIn(officialName, post.text()))
			.toList();
		if (supporting.size() < properties.getMinEvidencePosts()) {
			return KeywordInterpretation.unresolved(
				"근거 부족: '" + officialName + "'이(가) 들어 있는 근거 글 " + supporting.size() + "개");
		}
		if (!SearchMissTextMatcher.isRelated(keyword, officialName)) {
			return KeywordInterpretation.unresolved("검색어와 '" + officialName + "'의 관계 확인 실패");
		}
		return KeywordInterpretation.store(officialName, supporting.stream().map(NaverBlogPost::link).toList());
	}

	private String buildPrompt(String keyword, List<NaverBlogPost> posts) {
		String numberedPosts = IntStream.range(0, posts.size())
			.mapToObj(index -> (index + 1) + ". " + posts.get(index).title() + " — " + posts.get(index).description())
			.collect(Collectors.joining("\n"));
		return promptTemplate.replace("{keyword}", keyword).replace("{posts}", numberedPosts);
	}

	private static String loadPrompt() {
		try {
			return new ClassPathResource(PROMPT_FILE).getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new UncheckedIOException("실패 검색어 해석 프롬프트를 읽지 못했습니다: " + PROMPT_FILE, exception);
		}
	}

	/** LLM 응답 그대로. 검증 전이라 이 값을 밖으로 내보내지 않는다. */
	public record InterpretAnswer(
			String refersTo,
			String officialName,
			List<Integer> evidence) {
	}
}
