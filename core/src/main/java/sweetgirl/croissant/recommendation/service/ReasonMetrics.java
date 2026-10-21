package sweetgirl.croissant.recommendation.service;

import java.util.List;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 이유 문장이 무엇으로 만들어졌는지를 그라파나에서 볼 수 있게 센다.
 *
 * <p><b>라벨 카디널리티가 설계의 전부다.</b> 회원 id 나 가게 id 를 라벨로 쓰면 시계열이 회원 수 × 가게 수로
 * 늘어 프로메테우스가 무너진다. 그래서 회원을 <b>세 갈래로 접어</b> 라벨에 담는다. 개인 단위 추적은 메트릭의
 * 일이 아니다 — 그건 DB 조인({@code recommendation_feedback × user_taste_profile})이 한다.
 *
 * <p>지금 라벨 조합은 {@code 근거 6 × 재료 3 = 18} 시계열이다. 여기에 라벨을 하나 더 붙일 때는
 * 곱해진다는 것을 기억한다.
 *
 * <p><b>메트릭은 지금부터 쌓인다.</b> 지난 데이터를 보려면 DB 를 조회해야 한다 — 이미 쌓인
 * {@code recommendation_feedback} 과 프로필이 있다.
 *
 * <p><b>반응에는 근거 라벨을 달지 않는다.</b> "개인화 근거로 설명한 추천이 더 담기는가" 를 보려면 그 추천을
 * 무엇으로 설명했는지가 저장돼 있어야 하는데, 지금은 문장만 저장하고 근거는 버린다. 근거를
 * {@code recommendation.result} 에 같이 싣는 것은 응답 계약이 넓어지는 결정이라 따로 판단한다.
 * 재료 갈래만으로도 "재료가 있는 회원이 더 담는가" 는 볼 수 있어, 그것부터 센다.
 */
@Component
@RequiredArgsConstructor
public class ReasonMetrics {

	private static final String GENERATED = "reason.sentence.generated";
	private static final String TASTE_MATERIAL = "taste_material";
	private static final String EVIDENCE = "evidence";

	private final MeterRegistry meterRegistry;

	/**
	 * 회원이 가진 재료의 갈래 — 라벨 값 셋. {@code STRONG} 은 태그 근거를 쓸 수 있는 회원이고
	 * ({@code strongStoreCount} 가 문턱 이상), {@code WEAK} 는 종류나 태그는 있으나 그 문턱을 못 넘는 회원,
	 * {@code NONE} 은 아무 재료도 없는 회원이다.
	 *
	 * <p>이 세 갈래로 접는 이유는 질문이 "재료가 있는 회원이 더 만족하는가" 이기 때문이다. 회원마다 보면
	 * 답이 안 나오고, 하나로 뭉치면 질문 자체가 사라진다.
	 */
	public enum TasteMaterial {
		STRONG, WEAK, NONE;

		public static TasteMaterial of(TasteQuery taste, int minStrongStores) {
			if (taste.strongStoreCount() >= minStrongStores) {
				return STRONG;
			}
			boolean hasAny = !taste.topKinds().isEmpty() || !taste.inferredKinds().isEmpty()
				|| !taste.tags().isEmpty() || taste.hasAxis() || taste.hasLabels();
			return hasAny ? WEAK : NONE;
		}
	}

	/** 코스 한 벌이 무엇으로 설명됐는지 — 줄마다 센다 */
	public void recordGenerated(List<GeneratedReasonSentence> sentences, TasteQuery taste, int minStrongStores) {
		String material = TasteMaterial.of(taste, minStrongStores).name();
		for (GeneratedReasonSentence sentence : sentences) {
			meterRegistry.counter(GENERATED,
				EVIDENCE, sentence.evidence().type().name(),
				TASTE_MATERIAL, material).increment();
		}
	}

	/**
	 * 추천에 대한 반응 — 회원 재료 갈래를 달아 "재료가 있는 회원이 더 담는가" 를 그라파나에서 나눠 본다.
	 *
	 * <p>라벨은 {@code 행동 8 × 재료 3 = 24} 시계열이다. 가게 id 나 추천 id 는 달지 않는다 —
	 * 그건 {@code recommendation_feedback} 행이 이미 들고 있고, 라벨로 쓰면 시계열이 무한정 는다.
	 */
	public void recordFeedback(String action, TasteMaterial material) {
		meterRegistry.counter("recommendation.feedback", "action", action, TASTE_MATERIAL, material.name())
			.increment();
	}

	/** LLM 이 그 자리를 실제로 채웠는지 — filled · too_long · missing */
	public void recordLlmOutcome(String outcome, int lines) {
		if (lines > 0) {
			meterRegistry.counter("reason.llm.lines", "outcome", outcome).increment(lines);
		}
	}

}
