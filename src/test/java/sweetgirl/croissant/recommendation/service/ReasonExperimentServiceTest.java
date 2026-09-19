package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.config.ReasonExperimentProperties;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.request.ReasonExperimentRequest;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentResponse;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentRun;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.service.ReasonExperimentService.Reason;
import sweetgirl.croissant.recommendation.service.ReasonExperimentService.ReasonAnswer;
import sweetgirl.croissant.recommendation.service.ReasonExperimentService.WarmUpAnswer;
import sweetgirl.croissant.store.entity.StoreProfile;
import sweetgirl.croissant.store.repository.StoreProfileRepository;

/** 이유 문장 실험 (#662) — 조합 실행·실패 격리·비용 가드·블라인드. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReasonExperimentServiceTest {

	private static final long AREA_ID = 7L;
	private static final long MEMBER_ID = 42L;

	@Mock
	CourseAreaRepository courseAreaRepository;
	@Mock
	UserTasteProfileRepository userTasteProfileRepository;
	@Mock
	RecommendationCandidateJdbcRepository candidateRepository;
	@Mock
	LlmClientRegistry llmClientRegistry;
	@Mock
	StoreProfileRepository storeProfileRepository;

	RecommendationProperties recommendationProperties = new RecommendationProperties();
	ReasonExperimentProperties experimentProperties = new ReasonExperimentProperties();
	ReasonExperimentService service;

	@BeforeEach
	void setUp() {
		experimentProperties.setModels(List.of("GEMINI:gemini-3.5-flash-lite"));
		experimentProperties.setVariants(List.of(ReasonPromptVariant.A));
		experimentProperties.setRepeats(1);
		experimentProperties.setWarmUp(false);
		service = new ReasonExperimentService(courseAreaRepository, userTasteProfileRepository, candidateRepository,
			recommendationProperties, experimentProperties, storeProfileRepository,
			new ReasonPromptBuilder(recommendationProperties), llmClientRegistry, new ObjectMapper());

		given(llmClientRegistry.configuredProviders())
			.willReturn(List.of(LlmProvider.GEMINI, LlmProvider.OPENAI, LlmProvider.ANTHROPIC));
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.of(seongsu()));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(candidateRepository.findAreaCandidates(anyLong(), any(), anyDouble(), anyDouble(), anyInt(), anyString(),
			any(), any(), any(), anyInt())).willReturn(List.of(store(1L, "밀도"), store(2L, "베통")));
	}

	private static CourseArea seongsu() {
		return CourseArea.create("서울", "성수동", "성수동", null, 37.5446, 127.0557, 1200, 1200, null, null, 1);
	}

	/** 두 곳을 가깝게 두어 chainWithinHop 이 둘 다 잇게 한다 */
	private static CandidateStoreRow store(long storeId, String name) {
		return new CandidateStoreRow(storeId, name, "성수동2가", 37.5446 + storeId * 0.0001, 127.0557, null, 100,
			3.4, 1.2, 0.5, 5, 0.5, 0.8, -0.2, 0.4, Map.of("동네빵집", 1.0), List.of("소금빵"));
	}

	private static ReasonExperimentRequest request(List<String> models, List<ReasonPromptVariant> variants,
			Boolean blind) {
		return new ReasonExperimentRequest(MEMBER_ID, AREA_ID, 2, models, variants, 1, blind);
	}

	private static LlmResult<ReasonAnswer> answer(long latencyMs) {
		return new LlmResult<>(new ReasonAnswer(List.of(new Reason(1L, "문장 1"), new Reason(2L, "문장 2"))),
			LlmProvider.GEMINI, "gemini-3.5-flash-lite", latencyMs, 700, 90, 0, "{}");
	}

	@SuppressWarnings("unchecked")
	private void givenAnswer(String firstReason, String secondReason) {
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willAnswer(invocation -> new LlmResult<>(
				new ReasonAnswer(List.of(new Reason(1L, firstReason), new Reason(2L, secondReason))),
				LlmProvider.GEMINI, "gemini-3.5-flash-lite", 640L, 700, 90, 0, "{}"));
	}

	@Test
	@DisplayName("조합마다 문장과 계측값을 돌려준다")
	void run_returnsMeasuredRuns() {
		givenAnswer("바삭한 소금빵이 강한 집이에요", "동네에서 오래 사랑받은 집이에요");

		ReasonExperimentResponse response = service.run(request(null, null, false));

		assertThat(response.runs()).hasSize(1);
		assertThat(response.runs().get(0).label()).isEqualTo("GEMINI/gemini-3.5-flash-lite/A");
		assertThat(response.runs().get(0).latencyMedianMs()).isEqualTo(640L);
		assertThat(response.runs().get(0).samples()).isEqualTo(1);
		assertThat(response.runs().get(0).inputTokens()).isEqualTo(700);
		assertThat(response.runs().get(0).reasons()).hasSize(2);
		assertThat(response.runs().get(0).failure()).isNull();
		assertThat(response.blindEntries()).isNull();
	}

	@Test
	@DisplayName("입력에 룰 문구가 같이 실린다 — 이길 대상이 판에 있어야 한다")
	void run_carriesRuleReasonAsBaseline() {
		givenAnswer("문장 1", "문장 2");

		ReasonExperimentResponse response = service.run(request(null, null, false));

		assertThat(response.input().stores()).extracting("ruleReason")
			.containsOnly("이 동네에서 자주 담기는 빵집이에요");
		assertThat(response.input().areaName()).isEqualTo("성수동");
	}

	@Test
	@DisplayName("모델이 가게를 빠뜨려도 우리 순서와 가게 수는 그대로다")
	void run_alignsSentencesToOurOrder() {
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willAnswer(invocation -> new LlmResult<>(new ReasonAnswer(List.of(new Reason(2L, "두 번째만 왔어요"))),
				LlmProvider.GEMINI, "gemini-3.5-flash-lite", 500L, 700, 40, 0, "{}"));

		ReasonExperimentResponse response = service.run(request(null, null, false));

		assertThat(response.runs().get(0).reasons()).extracting("storeId").containsExactly(1L, 2L);
		assertThat(response.runs().get(0).reasons().get(0).reason()).isNull();
		assertThat(response.runs().get(0).reasons().get(1).reason()).isEqualTo("두 번째만 왔어요");
	}

	@Test
	@DisplayName("한 조합이 실패해도 나머지는 계속 돈다 — 판을 다시 돌리면 비용이 두 배다")
	void run_isolatesFailure() {
		experimentProperties.setVariants(List.of(ReasonPromptVariant.A, ReasonPromptVariant.D));
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willThrow(new LlmException(LlmErrorCode.RATE_LIMITED))
			.willAnswer(invocation -> new LlmResult<>(
				new ReasonAnswer(List.of(new Reason(1L, "문장 1"), new Reason(2L, "문장 2"))),
				LlmProvider.GEMINI, "gemini-3.5-flash-lite", 700L, 800, 90, 0, "{}"));

		ReasonExperimentResponse response = service.run(request(null, null, false));

		// 반복마다 순서를 섞으므로 어느 조합이 실패했는지는 고정이 아니다. 하나만 실패하고 나머지는 돈다는 게 요점
		assertThat(response.runs()).hasSize(2);
		assertThat(response.runs()).filteredOn(run -> run.failure() != null)
			.singleElement()
			.satisfies(run -> {
				assertThat(run.failure()).isEqualTo("LLM502_2");
				assertThat(run.failedSamples()).isEqualTo(1);
				assertThat(run.reasons()).isEmpty();
			});
		assertThat(response.runs()).filteredOn(run -> run.failure() == null).hasSize(1);
	}

	@Test
	@DisplayName("블라인드에는 LLM 문장만 섞는다 — 룰은 반복되는 고정 문장이라 섞어도 가려지지 않는다")
	void run_blindMixesOnlyLlmSentences() {
		givenAnswer("LLM 문장 1", "LLM 문장 2");

		ReasonExperimentResponse response = service.run(request(null, null, true));

		assertThat(response.runs()).isNull();
		// 가게 2곳 × 조합 1 = 2줄. 룰은 여기 없다
		assertThat(response.blindEntries()).hasSize(2);
		assertThat(response.blindEntries()).extracting("reason")
			.containsExactlyInAnyOrder("LLM 문장 1", "LLM 문장 2")
			.doesNotContain("이 동네에서 자주 담기는 빵집이에요");
		assertThat(response.blindEntries()).extracting("label").doesNotContain("GEMINI/gemini-3.5-flash-lite/A");
		assertThat(response.blindAnswerKey()).isNotBlank();
	}

	@Test
	@DisplayName("블라인드 입력에서 취향 일치도는 빠지고, 룰 문구는 2차 비교용으로 남는다")
	void run_blindHidesScoreButKeepsRuleReason() {
		givenAnswer("LLM 문장 1", "LLM 문장 2");

		ReasonExperimentResponse response = service.run(request(null, null, true));

		assertThat(response.input().stores()).extracting("tasteFit").containsOnlyNulls();
		assertThat(response.input().stores()).extracting("ruleReason")
			.containsOnly("이 동네에서 자주 담기는 빵집이에요");
	}

	@Test
	@DisplayName("계측 모드에서는 취향 일치도가 그대로 나온다")
	void run_measuredKeepsScore() {
		givenAnswer("문장 1", "문장 2");

		ReasonExperimentResponse response = service.run(request(null, null, false));

		assertThat(response.input().stores()).extracting("tasteFit").doesNotContainNull();
	}

	@Test
	@DisplayName("반복하면 지연의 중앙값과 최댓값을 낸다 — 한 번의 값으로는 결정 근거가 약하다")
	void run_repeatsAndSummarisesLatency() {
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willReturn(answer(300L), answer(900L), answer(500L));

		ReasonExperimentResponse response = service.run(new ReasonExperimentRequest(
			MEMBER_ID, AREA_ID, 2, null, List.of(ReasonPromptVariant.A), 3, false));

		ReasonExperimentRun run = response.runs().get(0);
		assertThat(run.samples()).isEqualTo(3);
		assertThat(run.failedSamples()).isZero();
		assertThat(run.latencyMedianMs()).isEqualTo(500L);
		assertThat(run.latencyMaxMs()).isEqualTo(900L);
	}

	@Test
	@DisplayName("재기 전에 벤더마다 한 번 데운다 — 연결 비용이 첫 조합에만 얹히지 않게")
	void run_warmsUpEachProviderOnce() {
		experimentProperties.setWarmUp(true);
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willReturn(answer(300L));

		service.run(request(List.of("GEMINI:gemini-3.5-flash-lite"),
			List.of(ReasonPromptVariant.A, ReasonPromptVariant.D), false));

		// 변형 둘이라 조합은 둘인데, 벤더가 하나이므로 워밍업은 한 번뿐이다
		verify(llmClientRegistry).generateStructured(any(LlmRequest.class), eq(WarmUpAnswer.class));
	}

	@Test
	@DisplayName("워밍업이 실패해도 판은 그대로 돈다 — 거기서 재려는 게 없다")
	void run_ignoresWarmUpFailure() {
		experimentProperties.setWarmUp(true);
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(WarmUpAnswer.class)))
			.willThrow(new LlmException(LlmErrorCode.UNAVAILABLE));
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willReturn(answer(300L));

		ReasonExperimentResponse response = service.run(request(null, List.of(ReasonPromptVariant.A), false));

		assertThat(response.runs()).hasSize(1);
		assertThat(response.runs()).extracting("failure").containsOnlyNulls();
	}

	@Test
	@DisplayName("키 없는 벤더는 판에서 빼고 무엇을 뺐는지 알려 준다 — 실패 줄로 상한을 잡아먹지 않게")
	void run_skipsUnconfiguredProviders() {
		given(llmClientRegistry.configuredProviders()).willReturn(List.of(LlmProvider.GEMINI));
		givenAnswer("문장 1", "문장 2");

		ReasonExperimentResponse response = service.run(request(
			List.of("GEMINI:gemini-3.5-flash-lite", "OPENAI:gpt-5.6-luna"), List.of(ReasonPromptVariant.A), false));

		assertThat(response.runs()).hasSize(1);
		assertThat(response.runs().get(0).provider()).isEqualTo(LlmProvider.GEMINI);
		assertThat(response.skippedProviders()).containsExactly(LlmProvider.OPENAI);
	}

	@Test
	@DisplayName("같은 모델을 두 번 적어도 한 번만 부른다 — 같은 값에 두 번 돈을 내지 않는다")
	void run_dedupesModels() {
		givenAnswer("문장 1", "문장 2");

		ReasonExperimentResponse response = service.run(request(
			List.of("GEMINI:gemini-3.5-flash-lite", "GEMINI:gemini-3.5-flash-lite"),
			List.of(ReasonPromptVariant.A), false));

		assertThat(response.runs()).hasSize(1);
	}

	@Test
	@DisplayName("조합 상한을 넘으면 돌리기 전에 막는다 — 비용 가드")
	void run_rejectsTooManyCombinations() {
		experimentProperties.setMaxCalls(1);

		assertThatThrownBy(() -> service.run(request(
			List.of("GEMINI:a", "OPENAI:b"), List.of(ReasonPromptVariant.A), false)))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.EXPERIMENT_TOO_MANY_COMBINATIONS);
		verify(llmClientRegistry, never()).generateStructured(any(), any());
	}

	@Test
	@DisplayName("모델 표기가 '벤더:모델' 이 아니면 400")
	void run_rejectsMalformedModel() {
		assertThatThrownBy(() -> service.run(request(List.of("gemini-3.5-flash-lite"), null, false)))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.EXPERIMENT_MODEL_INVALID);
	}

	@Test
	@DisplayName("모르는 벤더면 400")
	void run_rejectsUnknownProvider() {
		assertThatThrownBy(() -> service.run(request(List.of("BEDROCK:nova"), null, false)))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.EXPERIMENT_MODEL_INVALID);
	}

	@Test
	@DisplayName("상권에 후보가 없으면 400 — 부를 곳이 없다")
	void run_rejectsEmptyCandidates() {
		given(candidateRepository.findAreaCandidates(anyLong(), any(), anyDouble(), anyDouble(), anyInt(), anyString(),
			any(), any(), any(), anyInt())).willReturn(List.of());

		assertThatThrownBy(() -> service.run(request(null, null, false)))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.EXPERIMENT_NO_CANDIDATES);
	}

	@Test
	@DisplayName("없는 상권이면 404")
	void run_rejectsUnknownArea() {
		given(courseAreaRepository.findByIdAndIsActiveTrue(AREA_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.run(request(null, null, false)))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.COURSE_AREA_NOT_FOUND);
	}

	@Test
	@DisplayName("가게 수가 범위를 벗어나면 400")
	void run_rejectsStoreCountOutOfRange() {
		ReasonExperimentRequest tooMany = new ReasonExperimentRequest(MEMBER_ID, AREA_ID, 99, null, null, 1, false);

		assertThatThrownBy(() -> service.run(tooMany))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.STORE_COUNT_OUT_OF_RANGE);
	}

	@Test
	@DisplayName("시그니처 메뉴를 쓰는 변형(B)이 없으면 store_profile 을 읽지 않는다")
	void signatureMenus_notReadWithoutVariantB() {
		givenAnswer("문장 1", "문장 2");

		service.run(request(null, List.of(ReasonPromptVariant.A, ReasonPromptVariant.D), false));

		verify(storeProfileRepository, never()).findAllById(any());
	}

	@Test
	@DisplayName("B 가 있으면 고른 가게 id 로 한 번 읽어 B 프롬프트에 싣는다 — null · 공백 원소는 거르고 앞뒤 공백 · 중복을 없앤다")
	@SuppressWarnings("unchecked")
	void signatureMenus_readOnceForChosenStores_andCleaned() {
		givenAnswer("문장 1", "문장 2");
		given(storeProfileRepository.findAllById(any())).willReturn(List.of(
			StoreProfile.builder().storeId(1L).signatureMenus(new String[] {"뺑 스위스", null, " ", "잠봉뵈르 ", "뺑 스위스"})
				.build(),
			StoreProfile.builder().storeId(2L).signatureMenus(new String[] {null, ""}).build()));

		service.run(request(null, List.of(ReasonPromptVariant.B), false));

		ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
		verify(storeProfileRepository, times(1)).findAllById(ids.capture());
		assertThat(ids.getValue()).containsExactlyInAnyOrder(1L, 2L);
		ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
		verify(llmClientRegistry).generateStructured(sent.capture(), eq(ReasonAnswer.class));
		assertThat(sent.getValue().prompt()).contains("   시그니처 메뉴: 뺑 스위스, 잠봉뵈르\n");
		// 걸러서 비는 가게는 그 줄이 통째로 빠진다 — 가게 둘 중 하나에만 줄이 있다
		assertThat(sent.getValue().prompt().split("시그니처 메뉴:", -1)).hasSize(2);
	}

	@Test
	@DisplayName("시그니처 메뉴 조회는 워밍업보다 먼저다 — 조회가 실패하면 LLM 을 한 번도 부르지 않는다")
	void signatureMenus_readBeforeWarmUp() {
		experimentProperties.setWarmUp(true);
		given(storeProfileRepository.findAllById(any())).willThrow(new IllegalStateException("store_profile 조회 실패"));

		assertThatThrownBy(() -> service.run(request(null, List.of(ReasonPromptVariant.B), false)))
			.isInstanceOf(IllegalStateException.class);

		verify(llmClientRegistry, never()).generateStructured(any(), any());
	}

	@Test
	@DisplayName("워밍업이 켜져 있으면 프롬프트를 다 만든 뒤에 워밍업한다")
	void prompts_builtBeforeWarmUp() {
		experimentProperties.setWarmUp(true);
		givenAnswer("문장 1", "문장 2");

		service.run(request(null, List.of(ReasonPromptVariant.B), false));

		InOrder order = inOrder(storeProfileRepository, llmClientRegistry);
		order.verify(storeProfileRepository).findAllById(any());
		order.verify(llmClientRegistry).generateStructured(any(LlmRequest.class), eq(WarmUpAnswer.class));
	}

	@Test
	@DisplayName("유형이 있는 회원이어도 모델에 보내는 프롬프트에 유형 코드 · 이름이 없다")
	void prompt_hasNoTypeEvenWhenMemberHasOne() {
		UserTasteProfile quizzed = UserTasteProfile.createEmpty(MEMBER_ID, LocalDateTime.of(2026, 9, 19, 23, 0));
		quizzed.applyQuiz(List.of(Map.of("axis", "taste", "choice", "A")),
			TasteAxis.of(new BigDecimal("-1.000"), new BigDecimal("1.000"), new BigDecimal("-1.000"),
				new BigDecimal("-1.000")),
			"SCRO", LocalDateTime.of(2026, 9, 19, 23, 0));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.of(quizzed));
		givenAnswer("문장 1", "문장 2");

		service.run(request(null, List.of(ReasonPromptVariant.A, ReasonPromptVariant.B, ReasonPromptVariant.D), false));

		ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
		verify(llmClientRegistry, times(3)).generateStructured(sent.capture(), eq(ReasonAnswer.class));
		assertThat(sent.getAllValues())
			.allSatisfy(request -> assertThat(request.prompt()).doesNotContain("SCRO", "완벽주의 미식가", "유형"));
	}
}
