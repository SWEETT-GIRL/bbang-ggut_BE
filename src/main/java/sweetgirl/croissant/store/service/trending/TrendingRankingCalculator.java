package sweetgirl.croissant.store.service.trending;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import sweetgirl.croissant.store.config.StoreTrendingProperties;

/**
 * 급부상 판정 — 순수 계산. 자격(평소보다 확실히 늘었나)과 순위(후보끼리 누가 더 뜨나)를 나눈다.
 *
 * <p>하나의 점수 ln(1+recent) × 배율 로만 판정하면 평소대로 유명한 가게(배율 1.0)가 조금 뜬 작은 가게를 이긴다 — ln 항이 절대량에
 * 점수를 주기 때문이다(예: 평소 300·최근 300 → 5.7점, 평소 3·최근 10 → 4.5점). 그래서 배율과 최소 회원 수로 먼저 거르고, 통과한
 * 가게끼리만 점수로 세운다.
 */
final class TrendingRankingCalculator {

	private static final double HOURS_PER_DAY = 24.0;

	private TrendingRankingCalculator() {
		// 정적 유틸리티
	}

	/**
	 * @param storeId  가게
	 * @param recent   최근 창의 감쇠 가중 신호 합
	 * @param members  최근 창의 고유 회원 수
	 * @param baseline 평소 수준(하루 가중 신호)
	 */
	record Candidate(long storeId, double recent, long members, double baseline) {
	}

	record Ranked(long storeId, double score, double growth) {
	}

	/** age 시간 전 버킷에 곱할 감쇠 가중치 — 반감기마다 절반 */
	static double decayWeight(long ageHours, Duration halfLife) {
		return Math.pow(0.5, ageHours / (double)halfLife.toHours());
	}

	/**
	 * 평소 속도(하루 b)로 꾸준히 활동한 가게가 최근 창에서 갖게 될 recent 는 b × W 다. 시간 버킷마다 b/24 가 쌓이고 감쇠가 곱해지므로
	 * W = Σ 감쇠 가중치 ÷ 24.
	 */
	static double steadyEquivalentDays(Duration window, Duration halfLife) {
		double sum = 0;
		for (long age = 0; age < window.toHours(); age++) {
			sum += decayWeight(age, halfLife);
		}
		return sum / HOURS_PER_DAY;
	}

	static List<Ranked> rank(List<Candidate> candidates, StoreTrendingProperties properties) {
		double equivalentDays = steadyEquivalentDays(properties.getWindow(), properties.getHalfLife());
		double smoothing = properties.getSmoothing();
		return candidates.stream()
			.filter(candidate -> candidate.members() >= properties.getMinMembers())
			.map(candidate -> {
				double growth = (candidate.recent() + smoothing) / (candidate.baseline() * equivalentDays + smoothing);
				return new Ranked(candidate.storeId(), Math.log1p(candidate.recent()) * growth, growth);
			})
			.filter(ranked -> ranked.growth() >= properties.getMinGrowth())
			.sorted(Comparator.comparingDouble(Ranked::score).reversed())
			.limit(properties.getTopSize())
			.toList();
	}
}
