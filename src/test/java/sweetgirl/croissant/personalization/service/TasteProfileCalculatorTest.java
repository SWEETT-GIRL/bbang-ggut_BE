package sweetgirl.croissant.personalization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.personalization.config.PersonalizationProperties;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.InteractionType;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.personalization.enums.TasteTypeSource;
import sweetgirl.croissant.personalization.enums.TasteUserType;
import sweetgirl.croissant.personalization.repository.TasteSignal;

class TasteProfileCalculatorTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 4, 0);
	private static final TasteAxis ALL_PLUS = axis(1, 1, 1, 1);
	private static final TasteAxis ALL_MINUS = axis(-1, -1, -1, -1);

	private final TasteProfileCalculator calculator = new TasteProfileCalculator(new PersonalizationProperties());

	private static TasteAxis axis(double taste, double texture, double filling, double vibe) {
		return TasteAxis.of(BigDecimal.valueOf(taste).setScale(3), BigDecimal.valueOf(texture).setScale(3),
			BigDecimal.valueOf(filling).setScale(3), BigDecimal.valueOf(vibe).setScale(3));
	}

	private static TasteSignal signal(InteractionType type, long storeId, LocalDateTime at, TasteAxis storeAxis) {
		return new TasteSignal(type, storeId, null, null, at, storeAxis, Map.of(), null);
	}

	private static TasteSignal tagged(InteractionType type, long storeId, Map<String, Double> tags) {
		return new TasteSignal(type, storeId, null, null, NOW, null, tags, null);
	}

	private static TasteSignal view(long storeId, LocalDateTime at, Boolean converted, TasteAxis storeAxis) {
		return new TasteSignal(InteractionType.STORE_VIEW, storeId, null, converted, at, storeAxis, Map.of(), null);
	}

	private static TasteSignal bread(long storeId, String kind, LocalDateTime at) {
		return new TasteSignal(InteractionType.RECORD_BREAD, storeId, 900L, null, at, null, Map.of(), kind);
	}

	private static List<TasteSignal> repeat(InteractionType type, int count, LocalDateTime at) {
		List<TasteSignal> signals = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			signals.add(signal(type, 1000 + i, at, null));
		}
		return signals;
	}

	private static void assertAxis(TasteAxis actual, String taste, String texture, String filling, String vibe) {
		assertThat(actual).isNotNull();
		assertThat(actual.getTaste()).isEqualByComparingTo(taste);
		assertThat(actual.getTexture()).isEqualByComparingTo(texture);
		assertThat(actual.getFilling()).isEqualByComparingTo(filling);
		assertThat(actual.getVibe()).isEqualByComparingTo(vibe);
	}

	@Test
	@DisplayName("신호도 검사도 없으면 축·타입·출처·종류·유형이 전부 없고 태그는 빈 맵, 카운트 0")
	void empty() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(), null, null, 0, NOW);

		assertThat(snapshot.axis()).isNull();
		assertThat(snapshot.typeCode()).isNull();
		assertThat(snapshot.typeSource()).isNull();
		assertThat(snapshot.tags()).isEmpty();
		assertThat(snapshot.topKinds()).isNull();
		assertThat(snapshot.userType()).isNull();
		assertThat(snapshot.strongStoreCount()).isZero();
		assertThat(snapshot.breadSignalCount()).isZero();
	}

	@Test
	@DisplayName("검사만 있으면 검사 축 그대로(두 번째 version 이 오르지 않게 정확히 같아야 한다), 출처 QUIZ, 0 은 −극")
	void quizOnly_returnsQuizAxisExactly() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(), axis(0.5, -0.5, 0, 1), NOW, 0, NOW);

		assertThat(snapshot.axis()).isEqualTo(axis(0.5, -0.5, 0, 1));
		assertThat(snapshot.typeSource()).isEqualTo(TasteTypeSource.QUIZ);
		assertThat(snapshot.typeCode()).isEqualTo("PFRN");
	}

	@Test
	@DisplayName("가게 축 상속: 종류 가중치(저장 1 · 담기 1.5)로 가중 평균 — 출처 SIGNAL")
	void storeAxis_weightedByInteractionType() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			signal(InteractionType.SAVE, 1, NOW, ALL_PLUS),
			signal(InteractionType.COURSE_ADD, 2, NOW, ALL_MINUS)), null, null, 0, NOW);

		assertAxis(snapshot.axis(), "-0.200", "-0.200", "-0.200", "-0.200"); // (1 − 1.5) ÷ 2.5
		assertThat(snapshot.typeSource()).isEqualTo(TasteTypeSource.SIGNAL);
		assertThat(snapshot.typeCode()).isEqualTo("SFRO");
	}

	@Test
	@DisplayName("반감기 90일: 90일 전 신호는 절반 무게")
	void decay_halvesAtNinetyDays() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			signal(InteractionType.SAVE, 1, NOW.minusDays(90), ALL_PLUS),
			signal(InteractionType.SAVE, 2, NOW, ALL_MINUS)), null, null, 0, NOW);

		assertAxis(snapshot.axis(), "-0.333", "-0.333", "-0.333", "-0.333"); // (0.5 − 1) ÷ 1.5
	}

	@Test
	@DisplayName("검사 ×3 과 신호를 합산한다 — 검사 전부 +1, 저장 가게 전부 −1 이면 (3 − 1) ÷ 4 = 0.5, 출처 SIGNAL")
	void quiz_mergedWithSignals() {
		TasteProfileSnapshot snapshot = calculator.calculate(
			List.of(signal(InteractionType.SAVE, 1, NOW, ALL_MINUS)), ALL_PLUS, NOW, 0, NOW);

		assertAxis(snapshot.axis(), "0.500", "0.500", "0.500", "0.500");
		assertThat(snapshot.typeSource()).isEqualTo(TasteTypeSource.SIGNAL);
		assertThat(snapshot.typeCode()).isEqualTo("PCLN");
	}

	@Test
	@DisplayName("음수 신호는 그 가게 축의 반대쪽으로 당기고 분모는 |가중치| — 저장 → 해제로 상쇄되면 근거 없음(축 없음), 뺀 가게(−1 축)는 +쪽으로")
	void negativeSignals_pullAway_withAbsoluteDenominator() {
		TasteProfileSnapshot cancelled = calculator.calculate(List.of(
			signal(InteractionType.SAVE, 1, NOW, ALL_PLUS),
			signal(InteractionType.UNSAVE, 1, NOW, ALL_PLUS)), null, null, 0, NOW);
		TasteProfileSnapshot pushed = calculator.calculate(List.of(
			signal(InteractionType.SAVE, 1, NOW, ALL_PLUS),
			signal(InteractionType.COURSE_REMOVE, 2, NOW, ALL_MINUS)), null, null, 0, NOW);

		assertThat(cancelled.axis()).isNull(); // 합이 0 벡터 — 분모만 남아 "정확히 0 = −극" 타입이 확정되는 것을 막는다
		assertThat(cancelled.typeSource()).isNull();
		assertAxis(pushed.axis(), "1.000", "1.000", "1.000", "1.000"); // (1·1 + (−1)·(−1)) ÷ 2
	}

	@Test
	@DisplayName("검사 축도 검사 시각 기준으로 감쇠한다 — 검사 + 신호 회원의 축이 날이 지나도 그대로라 version 이 매일 오르지 않는다")
	void quizDecaysWithSignals_axisStableAcrossDays() {
		LocalDateTime tenDaysAgo = NOW.minusDays(10);
		List<TasteSignal> saved = List.of(signal(InteractionType.SAVE, 1, tenDaysAgo, ALL_MINUS));

		TasteProfileSnapshot today = calculator.calculate(saved, ALL_PLUS, tenDaysAgo, 0, NOW);
		TasteProfileSnapshot tomorrow = calculator.calculate(saved, ALL_PLUS, tenDaysAgo, 0, NOW.plusDays(1));
		TasteProfileSnapshot nextMonth = calculator.calculate(saved, ALL_PLUS, tenDaysAgo, 0, NOW.plusDays(30));

		assertAxis(today.axis(), "0.500", "0.500", "0.500", "0.500"); // (3d − d) ÷ (3d + d)
		assertThat(tomorrow.axis()).isEqualTo(today.axis());
		assertThat(nextMonth.axis()).isEqualTo(today.axis());
		assertThat(today.typeSource()).isEqualTo(TasteTypeSource.SIGNAL);
	}

	@Test
	@DisplayName("양의 신호가 하나도 없으면 행동 신호는 축을 만들지 않는다 — 조회만 하고 안 담은 회원은 축 없음, 검사가 있으면 검사 그대로")
	void negativeSignalsAlone_doNotShapeAxis() {
		List<TasteSignal> onlyPenalties = List.of(
			view(1, NOW.minusDays(20), false, ALL_PLUS),
			view(1, NOW.minusDays(10), false, ALL_PLUS),
			signal(InteractionType.RECO_DISMISSED, 2, NOW, ALL_MINUS));

		TasteProfileSnapshot withoutQuiz = calculator.calculate(onlyPenalties, null, null, 0, NOW);
		TasteProfileSnapshot withQuiz = calculator.calculate(onlyPenalties, axis(0.5, -0.5, 0, 1), NOW, 0, NOW);

		assertThat(withoutQuiz.axis()).isNull();
		assertThat(withoutQuiz.typeSource()).isNull();
		assertThat(withoutQuiz.userType()).isEqualTo(TasteUserType.BROWSER);
		assertThat(withQuiz.axis()).isEqualTo(axis(0.5, -0.5, 0, 1));
		assertThat(withQuiz.typeSource()).isEqualTo(TasteTypeSource.QUIZ);
	}

	@Test
	@DisplayName("선택 성향 보정: 저장 ÷ 조회일 = 1 이면 저장 가중치가 절반 — 담기(1.5)가 상대적으로 무거워진다")
	void saveWeight_correctedByConversionRate() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			signal(InteractionType.SAVE, 1, NOW, ALL_PLUS),
			signal(InteractionType.SAVE, 2, NOW, ALL_PLUS),
			view(1, NOW.minusDays(10), false, ALL_PLUS), // 저장한 가게의 조회는 부호 없음(강한 신호가 말한다)
			view(2, NOW.minusDays(10), false, ALL_PLUS),
			signal(InteractionType.COURSE_ADD, 3, NOW, ALL_MINUS)), null, null, 0, NOW);

		assertAxis(snapshot.axis(), "-0.200", "-0.200", "-0.200", "-0.200"); // (0.5 + 0.5 − 1.5) ÷ 2.5
	}

	@Test
	@DisplayName("조회: 같은 세션에 저장·담기(converted) 는 +2")
	void view_converted_isStrongPositive() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			view(1, NOW, true, ALL_PLUS),
			signal(InteractionType.COURSE_ADD, 2, NOW, ALL_MINUS)), null, null, 0, NOW);

		assertAxis(snapshot.axis(), "0.143", "0.143", "0.143", "0.143"); // (2 − 1.5) ÷ 3.5
	}

	@Test
	@DisplayName("조회: 7일 지나도 그 가게에 강한 신호가 없으면 −0.3, 7일 안이면 아직 0, 강한 신호가 있는 가게면 0")
	void view_notConverted_penalisedOnlyAfterGraceAndWithoutStrongSignal() {
		// 기준점은 담기(1.5) — 저장이면 저장 ÷ 조회일 보정이 같이 걸려 읽기 어렵다
		TasteProfileSnapshot penalised = calculator.calculate(List.of(
			signal(InteractionType.COURSE_ADD, 9, NOW, ALL_PLUS),
			view(1, NOW.minusDays(8), false, ALL_PLUS)), null, null, 0, NOW);
		TasteProfileSnapshot undecided = calculator.calculate(List.of(
			signal(InteractionType.COURSE_ADD, 9, NOW, ALL_PLUS),
			view(1, NOW.minusDays(3), false, ALL_PLUS)), null, null, 0, NOW);
		TasteProfileSnapshot spokenByStrong = calculator.calculate(List.of(
			signal(InteractionType.COURSE_ADD, 9, NOW, ALL_PLUS),
			signal(InteractionType.PLAN, 1, NOW.minusDays(40), ALL_MINUS),
			view(1, NOW.minusDays(30), false, ALL_MINUS)), null, null, 0, NOW);

		// (1.5 − 0.3·d8) ÷ (1.5 + 0.3·d8), d8 = 0.5^(8/90) = 0.9402 → 0.683
		assertAxis(penalised.axis(), "0.683", "0.683", "0.683", "0.683");
		assertAxis(undecided.axis(), "1.000", "1.000", "1.000", "1.000");
		// 계획(1.5 × d40)만 −1 로 기여, 조회는 0: (1.5 − 1.5·d40) ÷ (1.5 + 1.5·d40), d40 = 0.7349 → 0.153
		assertAxis(spokenByStrong.axis(), "0.153", "0.153", "0.153", "0.153");
	}

	@Test
	@DisplayName("조회: 두 날 이상 보고 끝내 안 담으면 −0.5 를 가장 최근 조회에 한 번만")
	void view_repeatedNotConverted_penalisedOnce() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			signal(InteractionType.COURSE_ADD, 9, NOW, ALL_PLUS),
			view(1, NOW.minusDays(20), false, ALL_PLUS),
			view(1, NOW.minusDays(10), false, ALL_PLUS)), null, null, 0, NOW);

		// (1.5 − 0.5·d10) ÷ (1.5 + 0.5·d10), d10 = 0.9259 → 0.528 (둘 다 −0.5 면 0.254, −0.3 씩이면 0.474)
		assertAxis(snapshot.axis(), "0.528", "0.528", "0.528", "0.528");
	}

	@Test
	@DisplayName("태그는 양의 신호 가게만의 사전 키별 가중 평균 — 값이 빠진 가게는 중립값(투표 0.5 · 규칙 0), 음수 신호 가게는 빠진다")
	void tags_weightedAverageOfPositiveSignals() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			tagged(InteractionType.SAVE, 1, Map.of("머물기", 1.0)),
			tagged(InteractionType.COURSE_ADD, 2, Map.of("가성비", 1.0)),
			tagged(InteractionType.UNSAVE, 3, Map.of("머물기", 1.0))), null, null, 0, NOW);

		assertThat(snapshot.tags()).hasSize(TasteTag.values().length)
			.containsEntry("머물기", 0.7) // (1 × 1.0 + 1.5 × 중립 0.5) ÷ 2.5
			.containsEntry("가성비", 0.8) // (1 × 중립 0.5 + 1.5 × 1.0) ÷ 2.5
			.containsEntry("디저트", 0.5) // 두 가게 다 값이 없어 투표 태그 중립값
			.containsEntry("웨이팅", 0.0); // 규칙 태그의 중립값은 0
	}

	@Test
	@DisplayName("top_kinds 는 기록 빵 종류의 decay 합 상위 5 — 동률은 이름순, 오래된 기록은 뒤로")
	void topKinds_decayedCountsLimitedToFive() {
		LocalDateTime yesterday = NOW.minusDays(1);
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			bread(1, "소금빵", NOW), bread(1, "소금빵", NOW), bread(2, "휘낭시에", NOW),
			bread(3, "케이크", NOW.minusDays(200)), bread(3, "케이크", NOW.minusDays(200)),
			bread(3, "케이크", NOW.minusDays(200)),
			bread(4, "식빵", yesterday), bread(4, "스콘", yesterday), bread(4, "바게트", yesterday)), null, null, 0, NOW);

		assertThat(snapshot.topKinds()).containsExactly("소금빵", "휘낭시에", "바게트", "스콘", "식빵");
		assertThat(snapshot.breadSignalCount()).isEqualTo(9);
	}

	@Test
	@DisplayName("일반 종류(빵 · 기타 · 요거트)는 top_kinds 에 넣지 않는다 — 기록 수에는 그대로 센다")
	void topKinds_skipsGenericKinds() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			bread(1, "빵", NOW), bread(1, "빵", NOW), bread(1, "빵", NOW), bread(2, "기타", NOW),
			bread(2, "요거트", NOW), bread(3, "소금빵", NOW)), null, null, 0, NOW);

		assertThat(snapshot.topKinds()).containsExactly("소금빵");
		assertThat(snapshot.breadSignalCount()).isEqualTo(6);
	}

	@Test
	@DisplayName("일반 종류만 기록한 회원은 종류 기록이 없는 회원과 같다")
	void topKinds_onlyGenericKinds_isNull() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(bread(1, "빵", NOW), bread(2, "기타", NOW)),
			null, null, 0, NOW);

		assertThat(snapshot.topKinds()).isNull();
	}

	@Test
	@DisplayName("사용자 유형(90일): 담기 5 → PLANNER, 기록 5 → ARCHIVER, 방문 3 → DOER, 저장 10·비율 ≥0.5 → COLLECTOR, 그 외 BROWSER")
	void userType_rulesInOrder() {
		LocalDateTime recent = NOW.minusDays(1);
		List<TasteSignal> plannerAndArchiver = new ArrayList<>(repeat(InteractionType.COURSE_ADD, 5, recent));
		plannerAndArchiver.addAll(repeat(InteractionType.RECORD_STORE, 5, recent));
		List<TasteSignal> collector = new ArrayList<>(repeat(InteractionType.SAVE, 10, recent));
		collector.addAll(repeat(InteractionType.STORE_VIEW, 5, recent));
		List<TasteSignal> browser = new ArrayList<>(repeat(InteractionType.SAVE, 10, recent));
		browser.addAll(repeat(InteractionType.STORE_VIEW, 40, recent));

		assertThat(calculator.calculate(plannerAndArchiver, null, null, 0, NOW).userType())
			.isEqualTo(TasteUserType.PLANNER);
		assertThat(calculator.calculate(repeat(InteractionType.RECORD_STORE, 5, recent), null, null, 0, NOW).userType())
			.isEqualTo(TasteUserType.ARCHIVER);
		assertThat(calculator.calculate(List.of(), null, null, 3, NOW).userType()).isEqualTo(TasteUserType.DOER);
		assertThat(calculator.calculate(collector, null, null, 0, NOW).userType()).isEqualTo(TasteUserType.COLLECTOR);
		assertThat(calculator.calculate(browser, null, null, 0, NOW).userType()).isEqualTo(TasteUserType.BROWSER);
		assertThat(
			calculator.calculate(repeat(InteractionType.SAVE, 1, NOW.minusDays(100)), null, null, 0, NOW).userType())
			.isNull();
	}

	@Test
	@DisplayName("strong_store_count 는 저장·계획·담기·기록 가게의 distinct, bread_signal_count 는 기록 빵 행 수")
	void counts() {
		TasteProfileSnapshot snapshot = calculator.calculate(List.of(
			signal(InteractionType.SAVE, 1, NOW, null),
			signal(InteractionType.COURSE_ADD, 1, NOW, null),
			signal(InteractionType.PLAN, 2, NOW, null),
			signal(InteractionType.RECORD_STORE, 3, NOW, null),
			bread(3, "소금빵", NOW),
			signal(InteractionType.UNSAVE, 4, NOW, null),
			view(5, NOW, null, null)), null, null, 0, NOW);

		assertThat(snapshot.strongStoreCount()).isEqualTo(3);
		assertThat(snapshot.breadSignalCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("가게 프로필이 없는 신호는 카운트에는 남지만 축을 만들지 못한다 — 검사만 축이 되고 출처는 QUIZ")
	void signalWithoutStoreProfile_countsButDoesNotShapeAxis() {
		TasteProfileSnapshot snapshot = calculator.calculate(
			List.of(signal(InteractionType.SAVE, 1, NOW, null)), axis(0.5, -0.5, 0, 1), NOW, 0, NOW);

		assertThat(snapshot.axis()).isEqualTo(axis(0.5, -0.5, 0, 1));
		assertThat(snapshot.typeSource()).isEqualTo(TasteTypeSource.QUIZ);
		assertThat(snapshot.strongStoreCount()).isEqualTo(1);
		assertThat(snapshot.userType()).isEqualTo(TasteUserType.BROWSER);
	}
}
