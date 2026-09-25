package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.config.ReasonSentenceProperties;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.response.ReasonSentencesResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationAreaSummary;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResult;
import sweetgirl.croissant.recommendation.dto.response.RecommendedStoreResponse;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.enums.ReasonSource;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.recommendation.service.ReasonLlmContract.Reason;
import sweetgirl.croissant.recommendation.service.ReasonLlmContract.ReasonAnswer;
import sweetgirl.croissant.store.repository.StoreProfileRepository;

/** 이유 문장 서빙 (#764) — 켜짐/꺼짐 · 재호출 없음 · 실패와 타임아웃의 폴백 · 소유 확인. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReasonSentenceCommandServiceTest {

	private static final long MEMBER_ID = 42L;
	private static final long RECOMMENDATION_ID = 900L;
	private static final String RULE_REASON = "이 동네에서 많이 찾는 곳이에요";

	@Mock
	RecommendationRepository recommendationRepository;
	@Mock
	UserTasteProfileRepository userTasteProfileRepository;
	@Mock
	StoreProfileRepository storeProfileRepository;
	@Mock
	LlmClientRegistry llmClientRegistry;

	RecommendationProperties recommendationProperties = new RecommendationProperties();
	ReasonSentenceProperties properties = new ReasonSentenceProperties();
	ObjectMapper objectMapper = new ObjectMapper();
	/** 기본은 부른 스레드에서 그대로 돌린다 — 시간을 재는 테스트만 진짜 줄을 쓴다 */
	Executor executor = Runnable::run;

	@BeforeEach
	void setUp() {
		properties.setEnabled(true);
		// 전용 레지스트리가 그 벤더를 들고 있어야 기동한다 — 전용 클라이언트 없는 벤더를 막는 검사(#765 리뷰)
		given(llmClientRegistry.configuredProviders()).willReturn(List.of(LlmProvider.GEMINI));
		given(userTasteProfileRepository.findById(MEMBER_ID)).willReturn(Optional.empty());
		given(storeProfileRepository.findAllById(anyList())).willReturn(List.of());
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willReturn(Optional.of(ruleRecommendation()));
		// 조건부 갱신이 이긴 것으로 둔다 — 진 경우는 따로 시험한다
		given(recommendationRepository.fillReasonsIfStillRule(any(), any(), any(), any(), any(), any()))
			.willReturn(1);
	}

	private ReasonSentenceCommandService service() {
		return new ReasonSentenceCommandService(recommendationRepository, userTasteProfileRepository,
			storeProfileRepository, new ReasonPromptBuilder(recommendationProperties), properties, objectMapper,
			new ReasonMetrics(new SimpleMeterRegistry()), llmClientRegistry, executor);
	}

	@Test
	@DisplayName("꺼져 있으면 LLM 을 부르지 않고 룰 문장을 그대로 준다")
	void fill_disabled_returnsRuleSentences() {
		properties.setEnabled(false);

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.fallback()).isTrue();
		assertThat(response.provider()).isEqualTo(Recommendation.RULE_PROVIDER);
		assertThat(response.latencyMs()).isZero();
		assertThat(response.reasons()).extracting("reason").containsExactly(RULE_REASON, RULE_REASON);
		verify(llmClientRegistry, never()).generateStructured(any(), any());
		verify(recommendationRepository, never()).fillReasonsIfStillRule(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("성공하면 문장이 바뀌고 추천 행에 출처가 남는다")
	void fill_success_replacesSentencesAndSaves() {
		givenAnswer(new Reason(1L, "소금빵이 유명한 집이에요"), new Reason(2L, "머무르기 좋은 공간이에요"));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.fallback()).isFalse();
		assertThat(response.provider()).isEqualTo("gemini");
		assertThat(response.model()).isEqualTo("gemini-3.5-flash-lite");
		assertThat(response.latencyMs()).isEqualTo(1_310L);
		assertThat(response.reasons()).extracting("reason")
			.containsExactly("소금빵이 유명한 집이에요", "머무르기 좋은 공간이에요");

		ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
		// 판 구분은 변형 이름 + 프롬프트 내용 해시다 — 문구를 깎으면 값이 저절로 달라진다(#768)
		String promptVersion = new ReasonPromptBuilder(recommendationProperties).versionOf(properties.getVariant());
		assertThat(promptVersion).startsWith("reason-B-");
		verify(recommendationRepository).fillReasonsIfStillRule(eq(RECOMMENDATION_ID), saved.capture(),
			eq("gemini"), eq("gemini-3.5-flash-lite"), eq(promptVersion), eq(Recommendation.RULE_PROVIDER));
		assertThat(storedOf(saved.getValue()).stores()).extracting("reason")
			.containsExactly("소금빵이 유명한 집이에요", "머무르기 좋은 공간이에요");
	}

	@Test
	@DisplayName("고른 가게 · 순서 · 거리는 그대로 두고 문장만 바꾼다")
	void fill_success_keepsRuleChoices() {
		givenAnswer(new Reason(1L, "새 문장 1"), new Reason(2L, "새 문장 2"));

		service().fill(MEMBER_ID, RECOMMENDATION_ID);

		ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
		verify(recommendationRepository).fillReasonsIfStillRule(eq(RECOMMENDATION_ID), saved.capture(), any(), any(),
			any(), any());
		RecommendationResult stored = storedOf(saved.getValue());
		assertThat(stored.stores()).extracting("storeId").containsExactly(1L, 2L);
		assertThat(stored.totalDistanceM()).isEqualTo(420);
		assertThat(stored.title()).isEqualTo("성수동 빵집 코스");
	}

	@Test
	@DisplayName("빠진 가게는 룰 문장을 그대로 둔다")
	void fill_partialAnswer_keepsRuleSentenceForMissingStore() {
		givenAnswer(new Reason(1L, "소금빵이 유명한 집이에요"));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.reasons()).extracting("reason").containsExactly("소금빵이 유명한 집이에요", RULE_REASON);
	}

	@Test
	@DisplayName("35자를 넘는 줄만 룰 문장으로 되돌린다 — 한 줄이 길다고 네 줄을 통째로 버리지 않는다")
	void fill_tooLongSentence_fallsBackPerLine() {
		String tooLong = "사진 남기기 좋고 머물기 좋은 대형 베이커리라 플레인 소금빵과 케이크를 즐기기 좋아요";
		assertThat(tooLong.length()).isGreaterThan(ReasonTemplates.MAX_LENGTH);
		givenAnswer(new Reason(1L, tooLong), new Reason(2L, "소금빵이 맛있어요"));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		// 긴 줄만 룰 문장으로 남고 짧은 줄은 LLM 문장이 나간다
		assertThat(response.reasons()).extracting("reason").containsExactly(RULE_REASON, "소금빵이 맛있어요");
	}

	@Test
	@DisplayName("딱 35자는 통과한다 — 경계")
	void fill_exactlyMaxLength_passes() {
		String exact = "가".repeat(ReasonTemplates.MAX_LENGTH);
		givenAnswer(new Reason(1L, exact), new Reason(2L, exact));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.reasons()).extracting("reason").containsExactly(exact, exact);
	}

	@Test
	@DisplayName("전부 길이 초과면 성공으로 저장하지 않는다 — 저장하면 provider 가 바뀌어 다음 호출이 막힌다")
	void fill_allTooLong_doesNotSaveAsSuccess() {
		String tooLong = "가".repeat(ReasonTemplates.MAX_LENGTH + 1);
		givenAnswer(new Reason(1L, tooLong), new Reason(2L, tooLong));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.reasons()).extracting("reason").containsExactly(RULE_REASON, RULE_REASON);
		assertThat(response.reasons()).noneMatch(reason -> reason.reason().startsWith("가가가"));
		// 한 줄도 못 썼으니 룰 폴백이다 — 이걸 성공으로 저장하면 다음 fill() 이 재호출을 막고 그대로 굳는다
		assertThat(response.fallback()).isTrue();
		assertThat(response.provider()).isEqualTo(Recommendation.RULE_PROVIDER);
		verify(recommendationRepository, never()).fillReasonsIfStillRule(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("한 줄이라도 쓰였으면 성공으로 저장한다 — 나머지가 길어도")
	void fill_partiallyTooLong_savesAsSuccess() {
		givenAnswer(new Reason(1L, "소금빵이 맛있어요"),
			new Reason(2L, "가".repeat(ReasonTemplates.MAX_LENGTH + 1)));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.reasons()).extracting("reason").containsExactly("소금빵이 맛있어요", RULE_REASON);
		assertThat(response.fallback()).isFalse();
		verify(recommendationRepository).fillReasonsIfStillRule(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("추천에 없는 가게 id 가 오면 그 줄은 적용도 집계도 되지 않는다 — 성공 수가 부풀지 않게")
	void fill_unknownStoreId_isNotCountedAsFilled() {
		givenAnswer(new Reason(1L, "소금빵이 맛있어요"), new Reason(999L, "엉뚱한 가게 문장이에요"));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		// 가게 2 는 룰 문장 그대로 — 999 는 추천에 없어 아무 자리도 채우지 못한다
		assertThat(response.reasons()).extracting("reason").containsExactly("소금빵이 맛있어요", RULE_REASON);
		assertThat(response.reasons()).extracting("storeId").containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("줄마다 출처가 실린다 — 길이 초과로 룰이 된 줄은 RULE, 적용된 줄은 LLM")
	void fill_carriesPerLineSource() {
		givenAnswer(new Reason(1L, "소금빵이 맛있어요"),
			new Reason(2L, "가".repeat(ReasonTemplates.MAX_LENGTH + 1)));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.reasons()).extracting("source")
			.containsExactly(ReasonSource.LLM, ReasonSource.RULE);
		// 묶음 fallback 은 "전부 룰인가" 라, 섞였을 때 false 다 — 그 값으로 줄별 출처를 추정하면 안 된다
		assertThat(response.fallback()).isFalse();
	}

	@Test
	@DisplayName("저장에 줄별 출처가 같이 남는다 — DTO 로 바꿨다 저장해도 사라지지 않게")
	void fill_savesReasonMeta() {
		givenAnswer(new Reason(1L, "소금빵이 맛있어요"),
			new Reason(2L, "가".repeat(ReasonTemplates.MAX_LENGTH + 1)));

		service().fill(MEMBER_ID, RECOMMENDATION_ID);

		ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.captor();
		verify(recommendationRepository).fillReasonsIfStillRule(any(), saved.capture(), any(), any(), any(), any());
		Object meta = saved.getValue().get(ReasonSentenceCommandService.REASON_META);
		assertThat(meta).isInstanceOf(Map.class);
		assertThat(((Map<?, ?>)meta).get(ReasonSentenceCommandService.LLM_STORE_IDS))
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
			.containsExactly(1L);
	}

	@Test
	@DisplayName("꺼져 있으면 모든 줄이 RULE 이다")
	void fill_disabled_allLinesAreRule() {
		properties.setEnabled(false);

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.reasons()).extracting("source").containsOnly(ReasonSource.RULE);
	}

	@Test
	@DisplayName("두 번째 호출은 저장된 문장을 주고 LLM 을 부르지 않는다")
	void fill_alreadyFilled_doesNotCallLlm() {
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willReturn(Optional.of(llmRecommendation()));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.fallback()).isFalse();
		assertThat(response.provider()).isEqualTo("gemini");
		assertThat(response.reasons()).extracting("reason").containsExactly("이미 받은 문장 1", "이미 받은 문장 2");
		verify(llmClientRegistry, never()).generateStructured(any(), any());
		verify(recommendationRepository, never()).fillReasonsIfStillRule(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("벤더가 실패해도 200 에 룰 문장을 담아 준다")
	void fill_llmFails_returnsRuleSentences() {
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willThrow(new LlmException(LlmErrorCode.GENERATE_FAILED));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.fallback()).isTrue();
		assertThat(response.reasons()).extracting("reason").containsExactly(RULE_REASON, RULE_REASON);
		verify(recommendationRepository, never()).fillReasonsIfStillRule(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("문장이 하나도 오지 않으면 룰 문장을 그대로 둔다")
	void fill_emptyAnswer_returnsRuleSentences() {
		givenAnswer();

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.fallback()).isTrue();
		assertThat(response.reasons()).extracting("reason").containsExactly(RULE_REASON, RULE_REASON);
		verify(recommendationRepository, never()).fillReasonsIfStillRule(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("하루 상한을 넘으면 부르지 않는다")
	void fill_dailyLimitExceeded_returnsRuleSentences() {
		properties.setDailyCallLimit(0);

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.fallback()).isTrue();
		verify(llmClientRegistry, never()).generateStructured(any(), any());
	}

	@Test
	@DisplayName("상한만큼만 부르고 그다음은 룰 문장이다")
	void fill_dailyLimit_countsCalls() {
		properties.setDailyCallLimit(1);
		givenAnswer(new Reason(1L, "문장 1"), new Reason(2L, "문장 2"));
		// 부를 때마다 아직 룰 문장인 행을 준다 — 아니면 두 번째는 "이미 채워진 묶음" 갈래로 빠져 상한을 재지 못한다
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willAnswer(invocation -> Optional.of(ruleRecommendation()));
		ReasonSentenceCommandService service = service();

		assertThat(service.fill(MEMBER_ID, RECOMMENDATION_ID).fallback()).isFalse();
		assertThat(service.fill(MEMBER_ID, RECOMMENDATION_ID).fallback()).isTrue();
	}

	@Test
	@DisplayName("정해진 시간을 넘으면 기다리지 않고 룰 문장을 준다")
	void fill_timeout_returnsRuleSentences() {
		properties.setTimeout(Duration.ofMillis(50));
		executor = Executors.newSingleThreadExecutor();
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willAnswer(invocation -> {
				Thread.sleep(2_000);
				return answer(new Reason(1L, "늦게 온 문장"));
			});

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.fallback()).isTrue();
		assertThat(response.reasons()).extracting("reason").containsExactly(RULE_REASON, RULE_REASON);
		verify(recommendationRepository, never()).fillReasonsIfStillRule(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("남의 추천은 찾을 수 없다")
	void fill_otherMembersRecommendation_throws() {
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> service().fill(MEMBER_ID, RECOMMENDATION_ID))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.RECOMMENDATION_NOT_FOUND);
	}

	@Test
	@DisplayName("앵커 추천에는 이유 문장이 없다")
	void fill_anchorRecommendation_throws() {
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willReturn(Optional.of(anchorRecommendation()));

		assertThatThrownBy(() -> service().fill(MEMBER_ID, RECOMMENDATION_ID))
			.isInstanceOf(RecommendationException.class)
			.hasFieldOrPropertyWithValue("errorCode", RecommendationErrorCode.ANCHOR_REASON_NOT_SUPPORTED);
	}

	@Test
	@DisplayName("모델 표기가 틀리면 기동 때 막는다 — 첫 요청에서 500 으로 터지지 않게 (#765 리뷰)")
	void construct_badModelReference_throws() {
		properties.setModel("제미나이-플래시");

		assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("제미나이-플래시");
	}

	@Test
	@DisplayName("전용 클라이언트가 없는 벤더면 기동 때 막는다")
	void construct_providerWithoutDedicatedClient_throws() {
		properties.setModel("OPENAI:gpt-5.6-luna");

		assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("OPENAI");
	}

	@Test
	@DisplayName("호출할 자리가 없으면 룰 문장을 주고 예산은 돌려준다 (#765 리뷰)")
	void fill_executorRejects_refundsBudget() {
		properties.setDailyCallLimit(1);
		executor = task -> {
			throw new RejectedExecutionException("자리 없음");
		};
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willAnswer(invocation -> Optional.of(ruleRecommendation()));
		ReasonSentenceCommandService service = service();

		assertThat(service.fill(MEMBER_ID, RECOMMENDATION_ID).fallback()).isTrue();

		// 나가지 않은 호출이라 예산이 그대로여야 한다 — 다음 요청이 상한에 걸리면 안 된다
		executor = Runnable::run;
		givenAnswer(new Reason(1L, "문장 1"), new Reason(2L, "문장 2"));
		assertThat(service().fill(MEMBER_ID, RECOMMENDATION_ID).fallback()).isFalse();
	}

	@Test
	@DisplayName("벤더 키가 없어 부르지도 못하면 예산을 돌려준다")
	void fill_providerNotConfigured_refundsBudget() {
		properties.setDailyCallLimit(1);
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willThrow(new LlmException(LlmErrorCode.PROVIDER_NOT_CONFIGURED));
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willAnswer(invocation -> Optional.of(ruleRecommendation()));
		ReasonSentenceCommandService service = service();

		assertThat(service.fill(MEMBER_ID, RECOMMENDATION_ID).fallback()).isTrue();

		givenAnswer(new Reason(1L, "문장 1"), new Reason(2L, "문장 2"));
		assertThat(service().fill(MEMBER_ID, RECOMMENDATION_ID).fallback()).isFalse();
	}

	@Test
	@DisplayName("다른 요청이 먼저 채웠으면 덮지 않고 그쪽 문장을 돌려준다 (#765 리뷰)")
	void fill_lostTheRace_returnsWinnersSentences() {
		givenAnswer(new Reason(1L, "내가 쓴 문장"), new Reason(2L, "내가 쓴 문장 2"));
		given(recommendationRepository.fillReasonsIfStillRule(any(), any(), any(), any(), any(), any()))
			.willReturn(0);
		given(recommendationRepository.findByIdAndMemberId(RECOMMENDATION_ID, MEMBER_ID))
			.willReturn(Optional.of(ruleRecommendation()), Optional.of(llmRecommendation()));

		ReasonSentencesResponse response = service().fill(MEMBER_ID, RECOMMENDATION_ID);

		assertThat(response.reasons()).extracting("reason").containsExactly("이미 받은 문장 1", "이미 받은 문장 2");
		assertThat(response.provider()).isEqualTo("gemini");
	}

	@Test
	@DisplayName("선정 근거 변형은 기동 때 막는다")
	void construct_ruleEvidenceVariant_throws() {
		properties.setVariant(ReasonPromptVariant.D);

		assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class);
	}

	@AfterEach
	void tearDown() {
		if (executor instanceof java.util.concurrent.ExecutorService running) {
			running.shutdownNow();
		}
	}

	private void givenAnswer(Reason... reasons) {
		given(llmClientRegistry.generateStructured(any(LlmRequest.class), eq(ReasonAnswer.class)))
			.willAnswer(invocation -> answer(reasons));
	}

	private static LlmResult<ReasonAnswer> answer(Reason... reasons) {
		return new LlmResult<>(new ReasonAnswer(List.of(reasons)), LlmProvider.GEMINI, "gemini-3.5-flash-lite",
			1_310L, 720, 95, 0, "{}");
	}

	private RecommendationResult storedOf(Map<String, Object> result) {
		return objectMapper.convertValue(result, RecommendationResult.class);
	}

	private Recommendation ruleRecommendation() {
		return withId(Recommendation.forArea(MEMBER_ID, 5L, 7L, (short)1, 3, Recommendation.RULE_PROVIDER, null, null,
			false, resultMap(RULE_REASON, RULE_REASON)));
	}

	private Recommendation llmRecommendation() {
		return withId(Recommendation.forArea(MEMBER_ID, 5L, 7L, (short)1, 3, "gemini", "gemini-3.5-flash-lite",
			"reason-A", false, resultMap("이미 받은 문장 1", "이미 받은 문장 2")));
	}

	private Recommendation anchorRecommendation() {
		return withId(Recommendation.forAnchor(MEMBER_ID, 5L, 11L, (short)1, 3, resultMap(RULE_REASON, RULE_REASON)));
	}

	private static Recommendation withId(Recommendation recommendation) {
		ReflectionTestUtils.setField(recommendation, "id", RECOMMENDATION_ID);
		return recommendation;
	}

	private Map<String, Object> resultMap(String firstReason, String secondReason) {
		RecommendationResult result = new RecommendationResult("성수동 빵집 코스",
			new RecommendationAreaSummary(7L, "성수동"), 420, false, 2,
			List.of(store(1L, "밀도", firstReason, 420), store(2L, "베통", secondReason, null)), false);
		return objectMapper.convertValue(result,
			objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
	}

	private static RecommendedStoreResponse store(long storeId, String name, String reason, Integer distanceToNextM) {
		return new RecommendedStoreResponse(storeId, name, "성수동2가", reason, 0.42, distanceToNextM,
			37.5446, 127.0557, null);
	}
}
