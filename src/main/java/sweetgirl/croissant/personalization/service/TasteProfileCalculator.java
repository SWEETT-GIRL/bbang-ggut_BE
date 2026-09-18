package sweetgirl.croissant.personalization.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;
import sweetgirl.croissant.personalization.repository.TasteSignal;

/**
 * 신호 목록 + 검사 축 → 프로필 값 (설계 §4.2 · §4.4 · §4.5). DB 도 시계도 모르는 순수 계산이라 단위 테스트가 전부 여기 있다.
 *
 * <ul>
 * <li>유효 가중치 = 종류 가중치 × 0.5^(경과일 ÷ 반감기). SAVE 는 선택 성향 보정(× (1 − min(1, 저장 ÷ 조회일) ÷ 2)).</li>
 * <li>조회(STORE_VIEW)는 가게 단위로 부호를 정한다 — converted 면 +2, 그 가게에 양의 강한 신호가 창 안에 있으면 0, 아직
 * 유예 기간(7일)이면 0, 나머지는 하루면 −0.3 · 두 날 이상이면 −0.5 를 가장 최근 조회에 한 번.</li>
 * <li>축 = (Σ w·d·가게축 + 검사 가중치·검사축) ÷ (Σ |w|·d + 검사 가중치). 분모를 |w| 로 두어 음수 신호가 반대쪽으로 당기되 분모가
 * 0 이하로 가지 않는다. 단 <b>양의 신호(축 있는 가게)가 하나도 없으면 행동 신호는 축에 들어가지 않는다</b> — 조회만 하고 안 담은
 * 회원의 축이 본 가게의 정반대가 되는 것을 막는다(랩 계측에서 140명). [−1, 1] 로 자르고 소수 3자리. 양의 신호가 기여했으면
 * SIGNAL, 검사만이면 QUIZ, 둘 다 없으면 축 없음(추천은 인기 prior 순).</li>
 * <li>태그 = 양의 유효 가중치 가게의 12키 가중 평균. top_kinds = RECORD_BREAD 종류의 decay 합 상위 N.</li>
 * <li>유형(§3.3, 90일) = PLANNER → ARCHIVER → DOER → COLLECTOR → BROWSER 순서로 첫 매치, 90일 신호가 없으면 null.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TasteProfileCalculator {

	/** 가게를 "좋아한다"고 읽는 강한 신호 — strong_store_count 의 재료이자 조회 페널티를 지우는 근거 */
	static final Set<InteractionType> STRONG_POSITIVE = EnumSet.of(InteractionType.SAVE, InteractionType.PLAN,
		InteractionType.COURSE_ADD, InteractionType.RECORD_STORE, InteractionType.RECORD_BREAD);
	private static final double MILLIS_PER_DAY = 24 * 60 * 60 * 1000d;
	private static final int AXIS_COUNT = 4;

	private final PersonalizationProperties personalizationProperties;

	/**
	 * @param signals 창 안 신호(시각 순이 아니어도 된다)
	 * @param quizAxis 검사 축 — 검사를 안 했으면 null
	 * @param visitCount 유형 창 안 방문 체크 수(course_store_visits)
	 */
	public TasteProfileSnapshot calculate(List<TasteSignal> signals, TasteAxis quizAxis, int visitCount,
			LocalDateTime now) {
		PersonalizationProperties.Profile profile = personalizationProperties.getProfile();
		List<Weighted> weighted = weigh(signals, now, profile);
		TasteAxis axis = null;
		TasteTypeSource typeSource = null;
		double[] axisSum = new double[AXIS_COUNT];
		double signalDenominator = 0;
		boolean hasPositiveAxisSignal = weighted.stream()
			.anyMatch(item -> item.signal().hasStoreAxis() && item.mass() > 0);
		for (Weighted item : weighted) {
			if (!hasPositiveAxisSignal || !item.signal().hasStoreAxis()) {
				continue;
			}
			double mass = item.mass();
			double[] storeAxis = toArray(item.signal().storeAxis());
			for (int i = 0; i < AXIS_COUNT; i++) {
				axisSum[i] += mass * storeAxis[i];
			}
			signalDenominator += Math.abs(mass);
		}
		double denominator = signalDenominator;
		if (quizAxis != null && quizAxis.isComplete()) {
			double[] quiz = toArray(quizAxis);
			for (int i = 0; i < AXIS_COUNT; i++) {
				axisSum[i] += profile.getQuizWeight() * quiz[i];
			}
			denominator += profile.getQuizWeight();
		}
		if (denominator > 0) {
			axis = TasteAxis.of(scaled(axisSum[0] / denominator), scaled(axisSum[1] / denominator),
				scaled(axisSum[2] / denominator), scaled(axisSum[3] / denominator));
			typeSource = signalDenominator > 0 ? TasteTypeSource.SIGNAL : TasteTypeSource.QUIZ;
		}
		String typeCode = axis != null ? axis.typeCode() : null;
		return new TasteProfileSnapshot(axis, typeCode, typeSource, tagsOf(weighted), topKindsOf(signals, now, profile),
			userTypeOf(signals, visitCount, now, profile), strongStoreCount(signals), breadSignalCount(signals));
	}

	private List<Weighted> weigh(List<TasteSignal> signals, LocalDateTime now,
			PersonalizationProperties.Profile profile) {
		long saveCount = signals.stream().filter(signal -> signal.type() == InteractionType.SAVE).count();
		long viewDays = signals.stream().filter(signal -> signal.type() == InteractionType.STORE_VIEW).count();
		double saveFactor = viewDays == 0 ? 1d : 1d - Math.min(1d, (double)saveCount / viewDays) / 2d;
		Map<Long, List<TasteSignal>> viewsByStore = new HashMap<>();
		Set<Long> strongStores = signals.stream()
			.filter(signal -> STRONG_POSITIVE.contains(signal.type()) && signal.storeId() != null)
			.map(TasteSignal::storeId)
			.collect(Collectors.toSet());
		List<Weighted> weighted = new ArrayList<>();
		for (TasteSignal signal : signals) {
			if (signal.type() == InteractionType.STORE_VIEW) {
				viewsByStore.computeIfAbsent(signal.storeId(), key -> new ArrayList<>()).add(signal);
				continue;
			}
			double weight = signal.type() == InteractionType.SAVE ? signal.type().getWeight() * saveFactor
				: signal.type().getWeight();
			if (weight != 0) {
				weighted.add(new Weighted(signal, weight, decay(signal.occurredAt(), now, profile)));
			}
		}
		viewsByStore.forEach((storeId, views) -> weighted.addAll(
			weighViews(views, strongStores.contains(storeId), now, profile)));
		return weighted;
	}

	/** 조회의 부호는 가게 단위다 — 클래스 설명의 규칙. */
	private List<Weighted> weighViews(List<TasteSignal> views, boolean storeHasStrongSignal, LocalDateTime now,
			PersonalizationProperties.Profile profile) {
		List<Weighted> weighted = new ArrayList<>();
		List<TasteSignal> notConverted = new ArrayList<>();
		for (TasteSignal view : views) {
			if (Boolean.TRUE.equals(view.converted())) {
				weighted
					.add(new Weighted(view, profile.getViewConvertedWeight(), decay(view.occurredAt(), now, profile)));
				continue;
			}
			boolean stillUndecided = view.occurredAt().plusDays(profile.getViewConversionGraceDays()).isAfter(now);
			if (storeHasStrongSignal || stillUndecided) {
				continue;
			}
			notConverted.add(view);
		}
		if (notConverted.isEmpty()) {
			return weighted;
		}
		TasteSignal latest = notConverted.stream().max(Comparator.comparing(TasteSignal::occurredAt)).orElseThrow();
		double weight = notConverted.size() >= 2 ? profile.getViewRepeatedNotConvertedWeight()
			: profile.getViewNotConvertedWeight();
		weighted.add(new Weighted(latest, weight, decay(latest.occurredAt(), now, profile)));
		return weighted;
	}

	private Map<String, Double> tagsOf(List<Weighted> weighted) {
		double[] sum = new double[TasteTag.values().length];
		double denominator = 0;
		for (Weighted item : weighted) {
			double mass = item.mass();
			if (mass <= 0 || !item.signal().hasStoreTags()) {
				continue;
			}
			for (TasteTag tag : TasteTag.values()) {
				sum[tag.ordinal()] += mass * item.signal().storeTags().getOrDefault(tag.getKey(), 0d);
			}
			denominator += mass;
		}
		Map<String, Double> tags = new LinkedHashMap<>();
		if (denominator > 0) {
			for (TasteTag tag : TasteTag.values()) {
				tags.put(tag.getKey(), scaled(sum[tag.ordinal()] / denominator).doubleValue());
			}
		}
		return tags;
	}

	private String[] topKindsOf(List<TasteSignal> signals, LocalDateTime now,
			PersonalizationProperties.Profile profile) {
		Map<String, Double> score = new HashMap<>();
		for (TasteSignal signal : signals) {
			if (signal.type() == InteractionType.RECORD_BREAD && signal.kind() != null) {
				score.merge(signal.kind(), decay(signal.occurredAt(), now, profile), Double::sum);
			}
		}
		if (score.isEmpty()) {
			return null;
		}
		return score.entrySet().stream()
			.sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
				.thenComparing(Map.Entry.comparingByKey()))
			.limit(profile.getTopKindsLimit())
			.map(Map.Entry::getKey)
			.toArray(String[]::new);
	}

	private TasteUserType userTypeOf(List<TasteSignal> signals, int visitCount, LocalDateTime now,
			PersonalizationProperties.Profile profile) {
		LocalDateTime since = now.minusDays(profile.getUserTypeWindowDays());
		List<TasteSignal> recent = signals.stream().filter(signal -> !signal.occurredAt().isBefore(since)).toList();
		long courseAdds = count(recent, InteractionType.COURSE_ADD);
		long records = count(recent, InteractionType.RECORD_STORE);
		long saves = count(recent, InteractionType.SAVE);
		long viewDays = count(recent, InteractionType.STORE_VIEW);
		double saveViewRatio = viewDays == 0 ? 1d : (double)saves / viewDays;
		if (courseAdds >= profile.getPlannerCourseAdds()) {
			return TasteUserType.PLANNER;
		}
		if (records >= profile.getArchiverRecords()) {
			return TasteUserType.ARCHIVER;
		}
		if (visitCount >= profile.getDoerVisits()) {
			return TasteUserType.DOER;
		}
		if (saves >= profile.getCollectorSaves() && saveViewRatio >= profile.getCollectorSaveViewRatio()) {
			return TasteUserType.COLLECTOR;
		}
		if (!recent.isEmpty() || visitCount > 0) {
			return TasteUserType.BROWSER;
		}
		return null;
	}

	private static int strongStoreCount(List<TasteSignal> signals) {
		return (int)signals.stream()
			.filter(signal -> STRONG_POSITIVE.contains(signal.type()) && signal.storeId() != null)
			.map(TasteSignal::storeId)
			.distinct()
			.count();
	}

	private static int breadSignalCount(List<TasteSignal> signals) {
		return (int)count(signals, InteractionType.RECORD_BREAD);
	}

	private static long count(List<TasteSignal> signals, InteractionType type) {
		return signals.stream().filter(signal -> signal.type() == type).count();
	}

	/** 0.5^(경과일 ÷ 반감기). 미래 시각(시계 어긋남)은 경과 0 으로 본다. */
	static double decay(LocalDateTime occurredAt, LocalDateTime now, PersonalizationProperties.Profile profile) {
		double days = Math.max(0d, Duration.between(occurredAt, now).toMillis() / MILLIS_PER_DAY);
		return Math.pow(0.5, days / profile.getHalfLifeDays());
	}

	private static double[] toArray(TasteAxis axis) {
		return new double[] {axis.getTaste().doubleValue(), axis.getTexture().doubleValue(),
			axis.getFilling().doubleValue(), axis.getVibe().doubleValue()};
	}

	/** [−1, 1] 로 자르고 소수 3자리 — 컬럼 NUMERIC(4,3) 과 검사 축의 자릿수와 같다. */
	private static BigDecimal scaled(double value) {
		double clamped = Math.max(-1d, Math.min(1d, value));
		return BigDecimal.valueOf(clamped).setScale(TasteAxis.SCALE, RoundingMode.HALF_UP);
	}

	/** 신호 하나의 유효 가중치 — 부호 있는 가중치와 반감기 감쇠. mass = weight × decay */
	record Weighted(TasteSignal signal, double weight, double decay) {
		double mass() {
			return weight * decay;
		}
	}
}
