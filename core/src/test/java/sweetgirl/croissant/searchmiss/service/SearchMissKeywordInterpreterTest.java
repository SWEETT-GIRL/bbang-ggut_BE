package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.client.NaverBlogSearchClient;
import sweetgirl.croissant.external.naver.dto.NaverBlogPost;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.searchmiss.service.SearchMissKeywordInterpreter.InterpretAnswer;

@ExtendWith(MockitoExtension.class)
class SearchMissKeywordInterpreterTest {

	private static final List<NaverBlogPost> POSTS = List.of(
		new NaverBlogPost("런베뮤 안국 웨이팅", "런던베이글뮤지엄 줄여서 런베뮤라고 부른다", "link1"),
		new NaverBlogPost("런베뮤 후기", "맛있는 베이글", "link2"),
		new NaverBlogPost("런던 베이글 뮤지엄 다녀옴", "런베뮤 오픈런 성공", "link3"));

	@Mock
	private LlmClientRegistry llmClientRegistry;

	@Mock
	private NaverBlogSearchClient naverBlogSearchClient;

	private SearchMissKeywordInterpreter interpreter;

	@BeforeEach
	void setUp() {
		interpreter = new SearchMissKeywordInterpreter(llmClientRegistry, naverBlogSearchClient,
			new SearchMissTriageProperties());
	}

	private void givenAnswer(InterpretAnswer answer) {
		given(naverBlogSearchClient.search(eq("런베뮤"), any(Integer.class))).willReturn(POSTS);
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(InterpretAnswer.class)))
			.willReturn(new LlmResult<>(answer, LlmProvider.GEMINI, "model", 0, 0, 0, 0, ""));
	}

	@Test
	@DisplayName("정식 이름이 근거 글 두 개 이상에 그대로 있고 검색어의 줄임말이면 가게로 확정한다")
	void interpret_store() {
		givenAnswer(new InterpretAnswer("STORE", "런던베이글뮤지엄", List.of(1, 3)));

		KeywordInterpretation result = interpreter.interpret("런베뮤");

		assertThat(result.kind()).isEqualTo(KeywordInterpretation.Kind.STORE);
		assertThat(result.officialName()).isEqualTo("런던베이글뮤지엄");
		assertThat(result.evidenceLinks()).containsExactly("link1", "link3");
	}

	@Test
	@DisplayName("프롬프트에 검색어와 번호 붙인 글을 넣고 구조화 응답을 요청한다")
	void interpret_buildsPrompt() {
		givenAnswer(new InterpretAnswer("UNKNOWN", "", List.of()));

		interpreter.interpret("런베뮤");

		ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
		verify(llmClientRegistry).generateStructured(captor.capture(), eq(InterpretAnswer.class));
		LlmRequest request = captor.getValue();
		assertThat(request.prompt()).contains("\"런베뮤\"").contains("1. 런베뮤 안국 웨이팅").contains("3. 런던 베이글 뮤지엄");
		assertThat(request.responseSchema()).isEqualTo(SearchMissKeywordInterpreter.RESPONSE_SCHEMA);
		assertThat(request.temperature()).isZero();
	}

	@Test
	@DisplayName("근거 글에 없는 이름은 LLM 이 지어낸 것으로 보고 확정하지 않는다")
	void interpret_rejectsHallucinatedName() {
		givenAnswer(new InterpretAnswer("STORE", "런던베이커리하우스", List.of(1, 2, 3)));

		KeywordInterpretation result = interpreter.interpret("런베뮤");

		assertThat(result.kind()).isEqualTo(KeywordInterpretation.Kind.UNRESOLVED);
		assertThat(result.reason()).contains("근거 부족");
	}

	@Test
	@DisplayName("같은 글 번호를 여러 번 대거나 없는 번호를 대도 근거 수가 부풀지 않는다")
	void interpret_ignoresDuplicateAndOutOfRangeEvidence() {
		givenAnswer(new InterpretAnswer("STORE", "런던베이글뮤지엄", List.of(1, 1, 9)));

		KeywordInterpretation result = interpreter.interpret("런베뮤");

		assertThat(result.kind()).isEqualTo(KeywordInterpretation.Kind.UNRESOLVED);
	}

	@Test
	@DisplayName("글자 관계가 없고 검색어와 함께 나온 글도 모자라면 확정하지 않는다")
	void interpret_rejectsUnrelatedName() {
		List<NaverBlogPost> posts = List.of(
			new NaverBlogPost("주말 빵투어", "어니언 들렀다", "link1"),
			new NaverBlogPost("빵지순례 후기", "어니언 또 감", "link2"));
		given(naverBlogSearchClient.search(eq("빵지순례"), any(Integer.class))).willReturn(posts);
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(InterpretAnswer.class)))
			.willReturn(new LlmResult<>(new InterpretAnswer("STORE", "어니언", List.of(1, 2)), LlmProvider.GEMINI,
				"model", 0, 0, 0, 0, ""));

		KeywordInterpretation result = interpreter.interpret("빵지순례");

		assertThat(result.kind()).isEqualTo(KeywordInterpretation.Kind.UNRESOLVED);
		assertThat(result.reason()).contains("관계 확인 실패").contains("함께 나온 글 1개");
	}

	@Test
	@DisplayName("별명 인정 — 글자 관계가 없어도 검색어와 이름이 같은 글에 둘 이상 함께 나오면 확정한다(떰즈비 ↔ 카페떰비)")
	void interpret_acceptsNicknameByCoOccurrence() {
		List<NaverBlogPost> posts = List.of(
			new NaverBlogPost("떰즈비 다녀옴", "카페떰비 크로플 맛집", "link1"),
			new NaverBlogPost("청주 카페", "떰즈비라고 부르는 카페떰비", "link2"));
		given(naverBlogSearchClient.search(eq("떰즈비"), any(Integer.class))).willReturn(posts);
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(InterpretAnswer.class)))
			.willReturn(new LlmResult<>(new InterpretAnswer("STORE", "카페떰비", List.of(1, 2)), LlmProvider.GEMINI,
				"model", 0, 0, 0, 0, ""));

		KeywordInterpretation result = interpreter.interpret("떰즈비");

		assertThat(result.kind()).isEqualTo(KeywordInterpretation.Kind.STORE);
		assertThat(result.officialName()).isEqualTo("카페떰비");
	}

	@Test
	@DisplayName("메뉴·지역이라는 해석은 가게 아님으로 돌려준다")
	void interpret_menu() {
		givenAnswer(new InterpretAnswer("MENU", "", List.of()));

		assertThat(interpreter.interpret("런베뮤").kind()).isEqualTo(KeywordInterpretation.Kind.NOT_A_STORE);
	}

	@Test
	@DisplayName("블로그 글이 없으면 LLM 을 부르지 않고 '글 없음'으로 돌려준다")
	void interpret_noPosts() {
		given(naverBlogSearchClient.search(eq("없는말"), any(Integer.class))).willReturn(List.of());

		KeywordInterpretation result = interpreter.interpret("없는말");

		assertThat(result.kind()).isEqualTo(KeywordInterpretation.Kind.NO_POSTS);
		verify(llmClientRegistry, never()).generateStructured(any(), any());
	}

	@Test
	@DisplayName("LLM 한도 초과는 그대로 던져 이번 회차를 멈추게 한다")
	void interpret_rateLimited() {
		given(naverBlogSearchClient.search(eq("런베뮤"), any(Integer.class))).willReturn(POSTS);
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(InterpretAnswer.class)))
			.willThrow(new LlmException(LlmErrorCode.RATE_LIMITED));

		assertThatThrownBy(() -> interpreter.interpret("런베뮤")).isInstanceOf(LlmException.class);
	}

	@Test
	@DisplayName("모델 표기가 잘못되면 기동에서 실패한다")
	void constructor_invalidModel() {
		SearchMissTriageProperties properties = new SearchMissTriageProperties();
		properties.setModel("gemini-only");

		assertThatThrownBy(() -> new SearchMissKeywordInterpreter(llmClientRegistry, naverBlogSearchClient,
			properties)).isInstanceOf(IllegalArgumentException.class);
	}
}
