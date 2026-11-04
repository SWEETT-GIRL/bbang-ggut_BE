package sweetgirl.croissant.store.config;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import sweetgirl.croissant.store.enums.TrendingSignal;

/**
 * 자동완성 급부상·스테디 노출의 손잡이. 계산은 {@code TrendingRankingCalculator}, 노출은 {@code StoreSearchService} 에 있다.
 *
 * <p>운영 실측(2026-10): 가게 하나가 하루에 받는 고유 방문자는 최대 1~4명, 28일 일평균 1위 가게도 0.71명이었다. 24시간 창으로는 판정할
 * 신호가 없어 7일 창 + 시간 감쇠로 넓히고, 조회 말고 찜·빵로그 등도 센다.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "store.trending")
public class StoreTrendingProperties {

	/** 끄면 신호를 쌓지 않고, 자동완성 순서도 기존(지역 → 가게)대로 둔다 */
	private boolean enabled = false;

	/** 신호별 가중치 — 조회보다 찜·빵로그가 강한 관심이다 */
	@NotNull
	private Map<TrendingSignal, Double> weights = defaultWeights();

	/** 최근 활동을 세는 창 */
	@NotNull
	private Duration window = Duration.ofDays(7);

	/** 시간 감쇠 반감기 — 오늘 몰린 활동이 일주일 전 활동보다 크게 반영되게 한다 */
	@NotNull
	private Duration halfLife = Duration.ofHours(36);

	/** 평소 수준을 재는 기간(최근 창 바로 앞) */
	@NotNull
	private Duration baselineWindow = Duration.ofDays(28);

	/** 증가 배율의 스무딩 k — 활동이 몇 건뿐인 가게가 배율로 튀는 것을 누른다: (recent + k) / (baseline·W + k) */
	@DecimalMin("0.1")
	private double smoothing = 5;

	/** 급부상 자격: 평소보다 이 배율 이상 늘었다 */
	@DecimalMin("1.0")
	private double minGrowth = 1.5;

	/** 급부상 자격: 최근 창 안에서 신호를 남긴 고유 회원이 이만큼 이상이다 — 한두 명이 띄우지 못하게 */
	@Min(1)
	private int minMembers = 3;

	/** 자동완성에서 지역보다 위로 올릴 급부상 가게 수 */
	@Min(0)
	private int maxPromoted = 2;

	/** 순위표에 담는 최대 가게 수 */
	@Min(1)
	private int topSize = 300;

	/**
	 * 순위표를 다시 만드는 주기. 운영 실측(2026-11)이 신호 하루 약 190건·자동완성 약 120건이라 5분은 대부분 같은 결과를 다시 만들었다.
	 * 트래픽이 늘면 배포 없이 줄인다.
	 */
	@NotNull
	private Duration refreshInterval = Duration.ofMinutes(30);

	/** Redis 를 못 읽을 때 직전 순위표를 계속 쓰는 최대 시간 — 지나면 급부상 없이 정렬한다. 갱신 두 번을 놓칠 때까지 버틴다 */
	@NotNull
	private Duration staleSnapshotLimit = Duration.ofMinutes(60);

	private static Map<TrendingSignal, Double> defaultWeights() {
		Map<TrendingSignal, Double> weights = new EnumMap<>(TrendingSignal.class);
		weights.put(TrendingSignal.VIEW, 1.0);
		weights.put(TrendingSignal.SAVE, 2.0);
		weights.put(TrendingSignal.PLAN, 2.0);
		weights.put(TrendingSignal.COURSE_ADD, 2.0);
		weights.put(TrendingSignal.RECORD, 3.0);
		return weights;
	}

	public double weightOf(TrendingSignal signal) {
		return weights.getOrDefault(signal, 0.0);
	}
}
