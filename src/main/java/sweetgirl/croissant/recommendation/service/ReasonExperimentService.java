package sweetgirl.croissant.recommendation.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
import sweetgirl.croissant.personalization.enums.BbtiType;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.config.ReasonExperimentProperties;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.dto.request.ReasonExperimentRequest;
import sweetgirl.croissant.recommendation.dto.response.BlindReasonEntry;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentInput;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentResponse;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentRun;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentStore;
import sweetgirl.croissant.recommendation.dto.response.ReasonSentence;
import sweetgirl.croissant.recommendation.entity.CourseArea;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.CourseAreaRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationCandidateJdbcRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 이유 문장 실험 (#662): 같은 추천 한 판을 벤더 × 모델 × 프롬프트 변형으로 돌려 지연·토큰·문장을 나란히 놓는다.
 *
 * <p><b>운영 데이터에 쓰지 않는다.</b> 결과는 응답과 로그로만 나가고 {@code recommendation} 행은 만들지 않는다 —
 * 실험 문장이 운영 추천에 섞이면 나중에 무엇이 룰이고 무엇이 실험인지 가릴 수 없다.
 *
 * <p>클래스에 {@code @Transactional} 을 걸지 않는다. 조회는 서로 독립이고, 무엇보다 <b>LLM 호출이 트랜잭션 안에
 * 들어가면 커넥션을 수 초씩 쥔 채 바깥을 기다린다</b> — 조합 수만큼 곱해지므로 커넥션 풀이 먼저 마른다.
 */
@Service
@RequiredArgsConstructor
public class ReasonExperimentService {

	private static final Logger log = LoggerFactory.getLogger(ReasonExperimentService.class);

	private static final String MODEL_SEPARATOR = ":";
	/** 원문 로그 상한 — 이유 네 줄 JSON 이면 넉넉하고, 사고 과정이 딸려 와도 로그를 덮지 않는다 */
	private static final int RAW_TEXT_LOG_LIMIT = 2_000;
	private static final String LABEL_SEPARATOR = "/";
	/** 문장이 표준 JSON 스키마 하나로 나가고, Gemini 용 대문자 번역은 어댑터가 한다 */
	private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
		"type", "object",
		"properties", Map.of(
			"reasons", Map.of(
				"type", "array",
				"items", Map.of(
					"type", "object",
					"properties", Map.of(
						"storeId", Map.of("type", "integer"),
						"reason", Map.of("type", "string")),
					"required", List.of("storeId", "reason"),
					"additionalProperties", false))),
		"required", List.of("reasons"),
		"additionalProperties", false);

	private final CourseAreaRepository courseAreaRepository;
	private final UserTasteProfileRepository userTasteProfileRepository;
	private final RecommendationCandidateJdbcRepository candidateRepository;
	private final RecommendationProperties recommendationProperties;
	private final ReasonExperimentProperties experimentProperties;
	private final ReasonPromptBuilder promptBuilder;
	private final LlmClientRegistry llmClientRegistry;
	private final ObjectMapper objectMapper;

	public ReasonExperimentResponse run(ReasonExperimentRequest request) {
		CourseArea area = courseAreaRepository.findByIdAndIsActiveTrue(request.areaId())
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.COURSE_AREA_NOT_FOUND));
		int storeCount = storeCountOf(request);

		UserTasteProfile profile = userTasteProfileRepository.findById(request.memberId()).orElse(null);
		TasteQuery taste = TasteQuery.of(profile);
		Pool pool = poolFor(request.memberId(), taste, area);
		List<CandidateStoreRow> ordered = CourseRecommendationCommandService
			.chainWithinHop(pool.candidates(), area.getMaxHopM(), storeCount);
		if (ordered.isEmpty()) {
			throw new RecommendationException(RecommendationErrorCode.EXPERIMENT_NO_CANDIDATES);
		}

		ReasonExperimentInput input = inputOf(area, profile, taste, pool.fallback(), ordered);
		Set<LlmProvider> skipped = new LinkedHashSet<>();
		List<ReasonExperimentRun> runs = runAll(combinationsOf(request, skipped), taste, profile, ordered);
		List<LlmProvider> skippedProviders = List.copyOf(skipped);
		if (!request.isBlind()) {
			return ReasonExperimentResponse.measured(input, runs, skippedProviders);
		}
		return blindOf(input, runs, skippedProviders);
	}

	/**
	 * 조합 하나 = 벤더 × 모델 × 변형.
	 *
	 * <p>키 없는 벤더는 <b>돌리기 전에 뺀다.</b> 넣어 두면 호출마다 실패 한 줄만 쌓이고 조합 상한까지 잡아먹어,
	 * 정작 돌릴 수 있는 조합이 상한에 걸린다. 같은 모델을 두 번 적은 경우도 여기서 없앤다 — 같은 값에 두 번
	 * 돈을 낼 이유가 없다. 상한 검사는 이 둘을 거른 뒤에 한다.
	 */
	private List<Combination> combinationsOf(ReasonExperimentRequest request, Set<LlmProvider> skipped) {
		List<String> models = isEmpty(request.models()) ? experimentProperties.getModels() : request.models();
		List<ReasonPromptVariant> variants = isEmpty(request.variants())
			? experimentProperties.getVariants()
			: request.variants();
		Set<LlmProvider> configured = Set.copyOf(llmClientRegistry.configuredProviders());

		List<ModelRef> modelRefs = new ArrayList<>();
		for (String model : models) {
			ModelRef modelRef = ModelRef.parse(model);
			if (!configured.contains(modelRef.provider())) {
				skipped.add(modelRef.provider());
				continue;
			}
			if (!modelRefs.contains(modelRef)) {
				modelRefs.add(modelRef);
			}
		}
		if (modelRefs.size() * variants.size() > experimentProperties.getMaxCombinations()) {
			throw new RecommendationException(RecommendationErrorCode.EXPERIMENT_TOO_MANY_COMBINATIONS);
		}

		List<Combination> combinations = new ArrayList<>();
		for (ModelRef modelRef : modelRefs) {
			for (ReasonPromptVariant variant : new LinkedHashSet<>(variants)) {
				combinations.add(new Combination(modelRef.provider(), modelRef.model(), variant));
			}
		}
		return combinations;
	}

	private List<ReasonExperimentRun> runAll(List<Combination> combinations, TasteQuery taste,
			UserTasteProfile profile, List<CandidateStoreRow> ordered) {
		String typeCode = profile == null ? null : profile.getTypeCode();
		Map<ReasonPromptVariant, String> prompts = new LinkedHashMap<>();
		List<ReasonExperimentRun> runs = new ArrayList<>(combinations.size());
		for (Combination combination : combinations) {
			String prompt = prompts.computeIfAbsent(combination.variant(),
				variant -> promptBuilder.build(variant, taste, typeCode, ordered));
			runs.add(runOne(combination, prompt, ordered));
		}
		return runs;
	}

	/** 한 조합의 실패가 나머지를 멈추지 않는다 — 판을 다시 처음부터 돌리면 비용이 두 배다 */
	private ReasonExperimentRun runOne(Combination combination, String prompt, List<CandidateStoreRow> ordered) {
		String label = combination.label();
		long startedAt = System.nanoTime();
		try {
			LlmRequest request = new LlmRequest(combination.provider(), combination.model(), prompt, RESPONSE_SCHEMA,
				experimentProperties.getTemperature(), experimentProperties.getMaxOutputTokens());
			LlmResult<ReasonAnswer> result = llmClientRegistry.generateStructured(request, ReasonAnswer.class);
			List<ReasonSentence> sentences = sentencesOf(result.value(), ordered);
			// 문장까지 남긴다. 조합이 길어져 프록시 제한(운영 120초)을 넘으면 클라이언트는 504 를 받는데,
			// 건수만 적어 두면 이미 돈을 낸 문장을 되찾을 길이 없다.
			log.info("이유 문장 실험 [조합={}, 지연={}ms, 입력토큰={}, 출력토큰={}, 문장={}]",
				label, result.latencyMs(), result.inputTokens(), result.outputTokens(),
				sentences.stream().map(ReasonSentence::reason).toList());
			// 빈 자리가 있으면 원문을 남긴다 — 모델이 가게를 빠뜨렸는지 다른 id 를 줬는지는 원문에만 있다
			if (sentences.stream().anyMatch(sentence -> sentence.reason() == null)) {
				log.warn("이유 문장 실험 빈 자리 [조합={}, 응답원문={}]", label, abbreviate(result.rawText()));
			}
			return ReasonExperimentRun.succeeded(label, combination.provider(), combination.model(),
				combination.variant(), result.latencyMs(), result.inputTokens(), result.outputTokens(),
				prompt.length(), sentences);
		} catch (LlmException e) {
			long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
			log.warn("이유 문장 실험 실패 [조합={}, 지연={}ms, 코드={}]",
				label, latencyMs, e.getLlmErrorCode().getCode(), e);
			return ReasonExperimentRun.failed(label, combination.provider(), combination.model(),
				combination.variant(), latencyMs, prompt.length(), e.getLlmErrorCode().getCode());
		}
	}

	private static String abbreviate(String rawText) {
		if (rawText == null) {
			return "(없음)";
		}
		return rawText.length() <= RAW_TEXT_LOG_LIMIT ? rawText : rawText.substring(0, RAW_TEXT_LOG_LIMIT) + "…(생략)";
	}

	/** 모델이 순서를 바꾸거나 가게를 빠뜨려도 판이 흐트러지지 않게, 우리 순서로 다시 맞춘다 */
	private List<ReasonSentence> sentencesOf(ReasonAnswer answer, List<CandidateStoreRow> ordered) {
		Map<Long, String> byStoreId = new LinkedHashMap<>();
		if (answer != null && answer.reasons() != null) {
			answer.reasons().stream()
				.filter(reason -> reason.storeId() != null && reason.reason() != null)
				.forEach(reason -> byStoreId.putIfAbsent(reason.storeId(), reason.reason().trim()));
		}
		return ordered.stream()
			.map(row -> new ReasonSentence(row.storeId(), row.name(), byStoreId.get(row.storeId())))
			.toList();
	}

	/**
	 * 가게마다 LLM 문장들을 섞어 라벨만 붙인다. 답표는 Base64 로 접어 같이 준다 — 암호가 아니라 눈가림이다.
	 *
	 * <p><b>룰 문구는 섞지 않는다.</b> 섞어도 가려지지 않기 때문이다 — 룰은 고정 문장 셋 중 하나라 여러 가게에
	 * 똑같이 반복되고, "가게마다 같은 말이 나오는 보기"가 곧 룰이다. 대신 {@code input.stores[].ruleReason} 에
	 * 그대로 두고, 1차 승자와 2차로 견주게 한다({@link BlindReasonEntry} 참고).
	 *
	 * <p>입력에서 취향 일치도는 뺀다. 점수가 높은 가게의 문장을 더 좋게 보게 만든다.
	 */
	private ReasonExperimentResponse blindOf(ReasonExperimentInput input, List<ReasonExperimentRun> runs,
			List<LlmProvider> skippedProviders) {
		List<BlindReasonEntry> entries = new ArrayList<>();
		Map<String, String> answerKey = new LinkedHashMap<>();
		int labelSequence = 1;
		for (ReasonExperimentStore store : input.stores()) {
			List<String[]> choices = new ArrayList<>();
			for (ReasonExperimentRun run : runs) {
				run.reasons().stream()
					.filter(sentence -> sentence.storeId().equals(store.storeId()) && sentence.reason() != null)
					.forEach(sentence -> choices.add(new String[] {run.label(), sentence.reason()}));
			}
			Collections.shuffle(choices);
			for (String[] choice : choices) {
				String label = "보기 " + labelSequence++;
				entries.add(new BlindReasonEntry(label, store.storeId(), store.name(), choice[1]));
				answerKey.put(label, choice[0]);
			}
		}
		return ReasonExperimentResponse.blind(blindInputOf(input), entries, encode(answerKey), skippedProviders);
	}

	private ReasonExperimentInput blindInputOf(ReasonExperimentInput input) {
		return new ReasonExperimentInput(input.areaId(), input.areaName(), input.typeCode(), input.typeLabel(),
			input.hasAxis(), input.tasteFallback(),
			input.stores().stream().map(ReasonExperimentStore::withoutScore).toList());
	}

	private String encode(Map<String, String> answerKey) {
		try {
			return Base64.getEncoder()
				.encodeToString(objectMapper.writeValueAsString(answerKey).getBytes(StandardCharsets.UTF_8));
		} catch (JsonProcessingException e) {
			// 답표를 못 접었다고 실험 결과까지 버릴 이유는 없다 — 문장은 이미 손에 있다
			log.warn("블라인드 답표를 접지 못했다", e);
			return null;
		}
	}

	private ReasonExperimentInput inputOf(CourseArea area, UserTasteProfile profile, TasteQuery taste,
			boolean fallback, List<CandidateStoreRow> ordered) {
		double threshold = recommendationProperties.getScore().getTasteCosineThreshold();
		List<ReasonExperimentStore> stores = ordered.stream()
			.map(row -> new ReasonExperimentStore(row.storeId(), row.name(), row.region3rd(),
				CourseRecommendationCommandService.tasteFitOf(row),
				CourseRecommendationCommandService.reasonOf(taste, row, fallback, threshold)))
			.toList();
		String typeCode = profile == null ? null : profile.getTypeCode();
		return new ReasonExperimentInput(area.getId(), area.getName(), typeCode, BbtiType.labelOrCode(typeCode),
			taste.hasAxis(), fallback, stores);
	}

	/**
	 * 후보 풀 — {@code CourseRecommendationCommandService#poolFor} 와 같은 규칙으로 뽑는다. 폴백 판정은
	 * {@link CourseRecommendationCommandService#isTasteFallback} 를 그대로 부르므로 임계가 갈릴 일이 없고,
	 * 여기 남은 복제는 "폴백이면 취향 항을 빼고 다시 조회한다" 두 줄뿐이다.
	 *
	 * <p><b>다만 제외 목록이 비어 있다.</b> 운영은 코스에 담긴 가게와 이미 보여 준 가게를 빼고 뽑는데, 실험 API 는
	 * 코스를 받지 않아 뺄 목록이 없다. 그래서 여기서 나오는 4곳은 <b>빈 코스에서 처음 받는 추천</b>과만 같다.
	 * 회원이 이미 그 동네에서 추천을 받았거나 코스에 가게를 담아 뒀다면 운영 화면과 갈린다.
	 */
	private Pool poolFor(Long memberId, TasteQuery taste, CourseArea area) {
		List<CandidateStoreRow> candidates = findAreaCandidates(memberId, taste, area);
		boolean fallback = CourseRecommendationCommandService.isTasteFallback(candidates, taste,
			recommendationProperties.getScore().getMinTasteCandidates());
		if (fallback) {
			candidates = findAreaCandidates(memberId, TasteQuery.empty(), area);
		}
		return new Pool(candidates, fallback);
	}

	private List<CandidateStoreRow> findAreaCandidates(Long memberId, TasteQuery taste, CourseArea area) {
		return candidateRepository.findAreaCandidates(memberId, taste, area.getCenterLat(), area.getCenterLng(),
			area.getRadiusM(), area.getSido(), area.getRegion2nd(), area.getRegion3rd(), Set.of(),
			recommendationProperties.getArea().getCandidateLimit());
	}

	private int storeCountOf(ReasonExperimentRequest request) {
		RecommendationProperties.Area areaRules = recommendationProperties.getArea();
		int storeCount = request.storeCount() != null ? request.storeCount() : areaRules.getDefaultStoreCount();
		if (storeCount < areaRules.getMinStoreCount() || storeCount > areaRules.getMaxStoreCount()) {
			throw new RecommendationException(RecommendationErrorCode.STORE_COUNT_OUT_OF_RANGE);
		}
		return storeCount;
	}

	private static boolean isEmpty(List<?> values) {
		return values == null || values.isEmpty();
	}

	private record Pool(List<CandidateStoreRow> candidates, boolean fallback) {
	}

	private record Combination(LlmProvider provider, String model, ReasonPromptVariant variant) {

		String label() {
			return provider.name() + LABEL_SEPARATOR + model + LABEL_SEPARATOR + variant.name();
		}
	}

	/** 설정·요청의 {@code 벤더:모델} 한 줄 */
	private record ModelRef(LlmProvider provider, String model) {

		static ModelRef parse(String value) {
			String[] parts = value == null ? new String[0] : value.split(MODEL_SEPARATOR, 2);
			if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
				throw new RecommendationException(RecommendationErrorCode.EXPERIMENT_MODEL_INVALID);
			}
			try {
				return new ModelRef(LlmProvider.valueOf(parts[0].trim().toUpperCase(Locale.ROOT)), parts[1].trim());
			} catch (IllegalArgumentException e) {
				throw new RecommendationException(RecommendationErrorCode.EXPERIMENT_MODEL_INVALID);
			}
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ReasonAnswer(List<Reason> reasons) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Reason(Long storeId, String reason) {
	}
}
