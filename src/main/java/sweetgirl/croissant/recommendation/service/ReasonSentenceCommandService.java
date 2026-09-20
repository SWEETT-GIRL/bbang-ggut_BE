package sweetgirl.croissant.recommendation.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.config.async.AsyncConfig;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmModelRef;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.config.ReasonLlmClientConfig;
import sweetgirl.croissant.recommendation.config.ReasonSentenceProperties;
import sweetgirl.croissant.recommendation.dto.response.ReasonSentence;
import sweetgirl.croissant.recommendation.dto.response.ReasonSentencesResponse;
import sweetgirl.croissant.recommendation.dto.response.RecommendationResult;
import sweetgirl.croissant.recommendation.dto.response.RecommendedStoreResponse;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.recommendation.service.ReasonLlmContract.ReasonAnswer;
import sweetgirl.croissant.store.entity.StoreProfile;
import sweetgirl.croissant.store.repository.StoreProfileRepository;

/**
 * 이미 만들어진 추천 묶음의 이유 문장을 LLM 으로 채운다 (#764).
 *
 * <p><b>코스를 만드는 호출과 나눈 이유.</b> 가게를 고르는 것은 룰이 1초 안에 끝내는데 문장은 1.3초쯤 더 걸린다. 한 호출에
 * 묶으면 코스가 문장을 기다리느라 늦는다. 앱은 코스를 먼저 그리고 이 API 로 문장만 뒤에 받는다 — 문장이 늦거나 못 와도
 * 화면에는 룰 문장이 이미 있다.
 *
 * <p><b>실패는 에러가 아니다.</b> 꺼져 있음 · 하루 상한 초과 · 타임아웃 · 벤더 실패 모두 200 에 룰 문장을 담아
 * {@code fallback = true} 로 답한다. 앱이 갈래를 하나만 그리게 하려는 것이다 — 여기서 4xx/5xx 를 내면 화면마다 예외 처리가
 * 생기고, 정작 사용자에게는 이미 읽을 문장이 있다.
 *
 * <p><b>클래스에 {@code @Transactional} 을 걸지 않는다.</b> LLM 호출이 트랜잭션 안에 들어가면 커넥션을 수 초씩 쥔 채
 * 바깥을 기다린다. 조회 · 호출 · 저장이 각각 따로 돈다. 그 사이 같은 묶음에 두 번 요청이 오면 나중 것이 이긴다 — 같은
 * 프롬프트에 temperature 0 이라 문장도 같고, 값이 어긋날 자리가 없다.
 */
@Service
public class ReasonSentenceCommandService {

	private static final Logger log = LoggerFactory.getLogger(ReasonSentenceCommandService.class);

	/** 하루 상한은 한국 날짜로 센다 — 운영 · 스테이징의 하루와 사용자의 하루가 같아야 읽힌다 */
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final RecommendationRepository recommendationRepository;
	private final UserTasteProfileRepository userTasteProfileRepository;
	private final StoreProfileRepository storeProfileRepository;
	private final ReasonPromptBuilder promptBuilder;
	private final LlmClientRegistry llmClientRegistry;
	private final ReasonSentenceProperties properties;
	private final ObjectMapper objectMapper;
	private final Executor reasonLlmExecutor;
	/** 기동 때 한 번 푼다 — 요청마다 다시 파싱하지 않고, 표기가 틀렸으면 배포에서 드러난다 */
	private final LlmModelRef model;

	private final Object callCountLock = new Object();
	private LocalDate callCountDate = LocalDate.now(ZONE);
	private int callsToday;

	public ReasonSentenceCommandService(
			RecommendationRepository recommendationRepository,
			UserTasteProfileRepository userTasteProfileRepository,
			StoreProfileRepository storeProfileRepository,
			ReasonPromptBuilder promptBuilder,
			ReasonSentenceProperties properties,
			ObjectMapper objectMapper,
			@Qualifier(ReasonLlmClientConfig.REASON_LLM_REGISTRY) LlmClientRegistry llmClientRegistry,
			@Qualifier(AsyncConfig.REASON_LLM_EXECUTOR) Executor reasonLlmExecutor) {
		this.recommendationRepository = recommendationRepository;
		this.userTasteProfileRepository = userTasteProfileRepository;
		this.storeProfileRepository = storeProfileRepository;
		this.promptBuilder = promptBuilder;
		this.llmClientRegistry = llmClientRegistry;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.reasonLlmExecutor = reasonLlmExecutor;
		// 설정 잘못은 첫 사용자 요청이 아니라 배포에서 드러나야 한다. 선정 근거 변형(D)은 저장된 추천에 인기도 점수가
		// 없어 근거 줄의 절반이 0 으로 나간다 — 프롬프트가 거짓 재료를 받는 것이라 조용히 넘어갈 일이 아니다.
		if (properties.getVariant().isIncludesRuleEvidence()) {
			throw new IllegalStateException(
				"이유 문장 서빙에는 선정 근거 변형을 쓸 수 없습니다(저장된 추천에 인기도가 없다): " + properties.getVariant());
		}
		// 모델 표기도 같은 자리에서 막는다. 요청 때 풀면 그 예외는 LlmException 이 아니라서 폴백에 걸리지 않고
		// 500 으로 나가며, 그 요청은 이미 하루 예산을 한 칸 쓴 뒤다(#765 리뷰).
		try {
			this.model = LlmModelRef.parse(properties.getModel());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("이유 문장 모델 설정이 잘못됐습니다: " + properties.getModel(), e);
		}
		// 전용 클라이언트가 없는 벤더를 가리키면 조용히 공용 클라이언트를 타는 대신 여기서 멈춘다
		if (properties.isEnabled() && !llmClientRegistry.configuredProviders().contains(model.provider())) {
			throw new IllegalStateException("이유 문장 전용 클라이언트가 없는 벤더입니다: " + model.provider());
		}
	}

	/**
	 * 내 추천 묶음의 이유 문장을 준다. 이미 LLM 문장이 있는 묶음은 부르지 않고 저장된 것을 그대로 준다 — 두 번 눌러도
	 * 돈이 두 번 나가지 않고, 문장이 눌릴 때마다 달라지지도 않는다.
	 */
	public ReasonSentencesResponse fill(Long memberId, Long recommendationId) {
		Recommendation recommendation = recommendationRepository.findByIdAndMemberId(recommendationId, memberId)
			.orElseThrow(() -> new RecommendationException(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		if (recommendation.isAnchor()) {
			throw new RecommendationException(RecommendationErrorCode.ANCHOR_REASON_NOT_SUPPORTED);
		}
		RecommendationResult stored = objectMapper.convertValue(recommendation.getResult(),
			RecommendationResult.class);
		if (!Recommendation.RULE_PROVIDER.equals(recommendation.getProvider())) {
			return storedOf(recommendation, stored);
		}
		if (stored.stores() == null || stored.stores().isEmpty()) {
			// 후보가 없어 빈 묶음으로 저장된 적은 없지만(그때는 행을 만들지 않는다), 옛 행과 픽스처를 위해 막는다
			return storedOf(recommendation, stored);
		}
		if (!properties.isEnabled()) {
			return storedOf(recommendation, stored);
		}
		if (!tryAcquireCall()) {
			log.warn("이유 문장 하루 상한 초과 [추천={}, 상한={}]", recommendationId, properties.getDailyCallLimit());
			return storedOf(recommendation, stored);
		}
		return callAndApply(recommendation, stored);
	}

	private ReasonSentencesResponse callAndApply(Recommendation recommendation, RecommendationResult stored) {
		// 가게 프로필은 한 번만 읽는다 — 태그 · 종류 · 시그니처 메뉴가 같은 행에 있다
		Map<Long, StoreProfile> profiles = profilesOf(stored);
		List<CandidateStoreRow> rows = promptRowsOf(stored, profiles);
		String prompt = promptBuilder.build(properties.getVariant(), tasteOf(recommendation.getMemberId()), rows,
			signatureMenusOf(profiles));
		LlmResult<ReasonAnswer> result = call(prompt, recommendation.getId());
		if (result == null) {
			return storedOf(recommendation, stored);
		}
		Map<Long, String> byStoreId = ReasonLlmContract.byStoreId(result.value());
		if (byStoreId.isEmpty()) {
			log.warn("이유 문장이 하나도 오지 않았다 [추천={}, 지연={}ms]", recommendation.getId(), result.latencyMs());
			return storedOf(recommendation, stored);
		}
		List<RecommendedStoreResponse> stores = stored.stores().stream()
			// 빠진 가게는 룰 문장을 그대로 둔다 — 빈 칸을 내려보내느니 앞서 보여 준 문장이 낫다
			.map(store -> byStoreId.containsKey(store.storeId())
				? store.withReason(byStoreId.get(store.storeId()))
				: store)
			.toList();
		RecommendationResult filled = stored.withStores(stores);
		String provider = model.provider().name().toLowerCase(Locale.ROOT);
		// 아직 룰 문장인 행에만 쓴다. 같은 묶음에 요청이 나란히 들어와도 저장은 한 번만 이기고, 진 쪽은 이긴 쪽의
		// 문장을 그대로 돌려준다 — 둘이 쓴 문장은 같지만(temperature 0) 덮어쓰기를 남겨 둘 이유가 없다(#765 리뷰)
		int filledRows = recommendationRepository.fillReasonsIfStillRule(recommendation.getId(), write(filled),
			provider, model.model(), promptBuilder.versionOf(properties.getVariant()), Recommendation.RULE_PROVIDER);
		if (filledRows == 0) {
			log.info("이유 문장을 다른 요청이 먼저 채웠다 [추천={}]", recommendation.getId());
			return recommendationRepository.findByIdAndMemberId(recommendation.getId(), recommendation.getMemberId())
				.map(latest -> storedOf(latest, objectMapper.convertValue(latest.getResult(),
					RecommendationResult.class)))
				.orElseGet(() -> storedOf(recommendation, stored));
		}
		log.info("이유 문장 [추천={}, 모델={}:{}, 지연={}ms, 입력토큰={}, 출력토큰={}, 채운가게={}/{}]",
			recommendation.getId(), provider, model.model(), result.latencyMs(), result.inputTokens(),
			result.outputTokens(), byStoreId.size(), stores.size());
		return new ReasonSentencesResponse(recommendation.getId(), provider, model.model(), false,
			result.latencyMs(), sentencesOf(filled));
	}

	/**
	 * 호출 한 번. 실패 · 타임아웃 · 줄 없음은 모두 null 로 돌려 룰 문장으로 넘긴다 — 부르는 쪽이 갈래를 하나만 갖게.
	 *
	 * <p>기다림을 끊어도 호출은 살아 있다({@code cancel} 은 HTTP 읽기를 멈추지 못한다). 그 문장은 버려진다 — 늦게 도착한
	 * 문장을 저장하면 사용자가 이미 룰 문장을 읽은 뒤에 화면과 저장된 값이 갈린다.
	 */
	private LlmResult<ReasonAnswer> call(String prompt, Long recommendationId) {
		LlmRequest request = new LlmRequest(model.provider(), model.model(), prompt,
			ReasonLlmContract.RESPONSE_SCHEMA, properties.getTemperature(), properties.getMaxOutputTokens());
		CompletableFuture<LlmResult<ReasonAnswer>> future;
		try {
			future = CompletableFuture.supplyAsync(
				() -> llmClientRegistry.generateStructured(request, ReasonAnswer.class), reasonLlmExecutor);
		} catch (RejectedExecutionException e) {
			// 호출이 나가지 않았으니 예산을 돌려준다 — 자리 거절이 몰리면 한 건도 안 부르고 상한을 태운다(#765 리뷰)
			releaseCall();
			log.warn("이유 문장 호출 자리 없음 [추천={}]", recommendationId);
			return null;
		}
		try {
			return future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("이유 문장 타임아웃 [추천={}, 상한={}ms]", recommendationId, properties.getTimeout().toMillis());
		} catch (ExecutionException e) {
			// 키가 없어 부르지도 못한 것은 돈이 안 나갔다. 벤더가 받아 놓고 실패한 것은 나갔으니 그대로 센다
			if (e.getCause() instanceof LlmException failure
				&& failure.getLlmErrorCode() == LlmErrorCode.PROVIDER_NOT_CONFIGURED) {
				releaseCall();
			}
			log.warn("이유 문장 실패 [추천={}]", recommendationId, e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("이유 문장 대기 중 끊겼다 [추천={}]", recommendationId);
		}
		return null;
	}

	/**
	 * 프롬프트 재료는 <b>저장된 묶음</b>과 {@code store_profile} 로만 만든다. 후보 SQL 을 다시 돌리지 않는다 — 고른 가게는
	 * 이미 정해졌고, 다시 뽑으면 그 사이 데이터가 바뀌어 문장이 다른 가게를 말할 수 있다.
	 *
	 * <p>거리 · 인기도 · 축은 채우지 않는다. 쓰는 변형이 없기 때문이다(그래서 선정 근거 변형을 기동 때 막는다).
	 */
	private List<CandidateStoreRow> promptRowsOf(RecommendationResult stored, Map<Long, StoreProfile> profiles) {
		List<CandidateStoreRow> rows = new ArrayList<>(stored.stores().size());
		for (RecommendedStoreResponse store : stored.stores()) {
			StoreProfile profile = profiles.get(store.storeId());
			rows.add(new CandidateStoreRow(store.storeId(), store.name(), store.region3rd(), store.latitude(),
				store.longitude(), store.thumbnailImageKey(), 0, 0, 0, store.tasteFit(), 0,
				null, null, null, null,
				profile == null || profile.getTags() == null ? Map.of() : profile.getTags(),
				profile == null ? List.of() : kindsOf(profile.getKinds()),
				profile == null ? List.of() : kindsOf(profile.getSignatureKinds())));
		}
		return rows;
	}

	private Map<Long, StoreProfile> profilesOf(RecommendationResult stored) {
		Map<Long, StoreProfile> profiles = new LinkedHashMap<>();
		storeProfileRepository.findAllById(stored.stores().stream().map(RecommendedStoreResponse::storeId).toList())
			.forEach(profile -> profiles.put(profile.getStoreId(), profile));
		return profiles;
	}

	private Map<Long, List<String>> signatureMenusOf(Map<Long, StoreProfile> profiles) {
		if (!properties.getVariant().isIncludesSignatureMenus()) {
			return Map.of();
		}
		Map<Long, List<String>> menus = new LinkedHashMap<>();
		profiles.forEach((storeId, profile) -> {
			List<String> names = ReasonPromptBuilder.menuNamesOf(profile.getSignatureMenus());
			if (!names.isEmpty()) {
				menus.put(storeId, names);
			}
		});
		return menus;
	}

	/** 종류도 대표 종류도 dataops 의 TEXT[] 라 NULL 원소가 올 수 있다 — {@code List.of} 는 거기서 NPE 다 */
	private static List<String> kindsOf(String[] kinds) {
		if (kinds == null) {
			return List.of();
		}
		return Arrays.stream(kinds).filter(StringUtils::hasText).toList();
	}

	private TasteQuery tasteOf(Long memberId) {
		return TasteQuery.of(userTasteProfileRepository.findById(memberId).orElse(null));
	}

	/** 저장된 문장 그대로 — LLM 을 부르지 않았거나 이미 LLM 문장이 있는 묶음 */
	private ReasonSentencesResponse storedOf(Recommendation recommendation, RecommendationResult stored) {
		boolean rule = Recommendation.RULE_PROVIDER.equals(recommendation.getProvider());
		return new ReasonSentencesResponse(recommendation.getId(), recommendation.getProvider(),
			recommendation.getModel(), rule, 0, sentencesOf(stored));
	}

	private static List<ReasonSentence> sentencesOf(RecommendationResult result) {
		if (result.stores() == null) {
			return List.of();
		}
		return result.stores().stream()
			.map(store -> new ReasonSentence(store.storeId(), store.name(), store.reason()))
			.toList();
	}

	/**
	 * 하루 상한 — 실제로 나가는 호출만 센다(타임아웃으로 버린 것도 돈은 나갔으니 센다). 인스턴스 메모리라 여러 대가 되면
	 * 대수만큼 늘어난다. 스테이징 1대 전제이고, 운영에 켤 때는 공유 카운터로 옮긴다.
	 */
	private boolean tryAcquireCall() {
		synchronized (callCountLock) {
			LocalDate today = LocalDate.now(ZONE);
			if (!today.equals(callCountDate)) {
				callCountDate = today;
				callsToday = 0;
			}
			if (callsToday >= properties.getDailyCallLimit()) {
				return false;
			}
			callsToday++;
			return true;
		}
	}

	/** 나가지 않은 호출의 예산을 돌려준다 — 차감과 같은 잠금 안에서 */
	private void releaseCall() {
		synchronized (callCountLock) {
			if (callsToday > 0) {
				callsToday--;
			}
		}
	}

	private Map<String, Object> write(Object result) {
		return objectMapper.convertValue(result,
			objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
	}
}
