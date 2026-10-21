package sweetgirl.croissant.store.service.trending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.service.trending.TrendingRankingCalculator.Candidate;
import sweetgirl.croissant.store.service.trending.TrendingRankingCalculator.Ranked;

class TrendingRankingCalculatorTest {

	private final StoreTrendingProperties properties = new StoreTrendingProperties();

	/** 평소 하루 b 로 꾸준한 가게가 최근 창에서 갖는 recent */
	private double steadyRecent(double baseline) {
		return baseline * TrendingRankingCalculator.steadyEquivalentDays(properties.getWindow(),
			properties.getHalfLife());
	}

	@Test
	@DisplayName("감쇠는 반감기마다 절반이다")
	void decayWeight_halvesEveryHalfLife() {
		Duration halfLife = Duration.ofHours(36);

		assertThat(TrendingRankingCalculator.decayWeight(0, halfLife)).isEqualTo(1.0);
		assertThat(TrendingRankingCalculator.decayWeight(36, halfLife)).isCloseTo(0.5, within(1e-12));
		assertThat(TrendingRankingCalculator.decayWeight(72, halfLife)).isCloseTo(0.25, within(1e-12));
	}

	@Test
	@DisplayName("감쇠가 없으면 W 는 창의 일수와 같다 — 하루 b 씩 7일이면 7b")
	void steadyEquivalentDays_equalsWindowDaysWithoutDecay() {
		double days = TrendingRankingCalculator.steadyEquivalentDays(Duration.ofDays(7), Duration.ofDays(100_000));

		assertThat(days).isCloseTo(7.0, within(1e-3));
	}

	@Test
	@DisplayName("평소대로 유명한 가게는 배율이 1 이라 후보가 아니다 — 하나의 점수로 섞으면 조금 뜬 작은 가게를 이긴다")
	void steadyFamousStore_isNotTrending() {
		Candidate famous = new Candidate(1L, steadyRecent(300), 500, 300);
		Candidate risingSmall = new Candidate(2L, steadyRecent(3) * 3, 10, 3);

		List<Ranked> ranked = TrendingRankingCalculator.rank(List.of(famous, risingSmall), properties);

		assertThat(ranked).extracting(Ranked::storeId).containsExactly(2L);
	}

	@Test
	@DisplayName("유명한 가게도 평소보다 확 늘면(연휴 300 → 600) 후보가 된다")
	void famousStoreDoubling_isTrending() {
		Candidate famous = new Candidate(1L, steadyRecent(600), 900, 300);

		assertThat(TrendingRankingCalculator.rank(List.of(famous), properties)).extracting(Ranked::storeId)
			.containsExactly(1L);
	}

	@Test
	@DisplayName("새로 생긴 가게는 평소가 0 이라 최소 회원 수만 넘으면 잡힌다")
	void newStore_isTrendingOnceEnoughMembers() {
		Candidate newStore = new Candidate(1L, 12, 3, 0);
		Candidate tooFewMembers = new Candidate(2L, 40, 2, 0);

		assertThat(TrendingRankingCalculator.rank(List.of(newStore, tooFewMembers), properties))
			.extracting(Ranked::storeId).containsExactly(1L);
	}

	@Test
	@DisplayName("후보끼리는 ln(1+recent) × 배율 순이고 상한까지만 싣는다")
	void ranked_isSortedByScoreAndLimited() {
		properties.setTopSize(2);
		List<Candidate> candidates = List.of(
			new Candidate(1L, 10, 5, 0),
			new Candidate(2L, 50, 5, 0),
			new Candidate(3L, 30, 5, 0));

		List<Ranked> ranked = TrendingRankingCalculator.rank(candidates, properties);

		assertThat(ranked).extracting(Ranked::storeId).containsExactly(2L, 3L);
		assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
	}
}
