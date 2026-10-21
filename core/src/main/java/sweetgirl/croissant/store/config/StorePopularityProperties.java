package sweetgirl.croissant.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** 가게 인기도(store.popularity_score) 산식의 손잡이 (#663 ③). 산식 자체는 {@code StorePopularityRepository} 에 있다. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "store.popularity")
public class StorePopularityProperties {

	/**
	 * 조회 회원 수를 깎는 전환율 보정의 스무딩 표본 수 k(1 이상 — 0 이면 SQL 이 0 으로 나눈다):
	 * min(1, ((찜 + 담기) + k·p0) ÷ (조회 회원 + k) ÷ p0). 평균(p0) 이상으로 담기면 조회를 그대로 세고, 평균보다 덜 담기면 그 비율만큼
	 * 깎는다. 조회 회원이 k 보다 훨씬 적은 가게는 p0 쪽으로 끌려간다.
	 */
	@Min(1)
	private int conversionSmoothing = 10;

	/**
	 * 전국 랭킹 점수를 행동 점수와 같은 눈금으로 맞추는 분위. 두 분포(0 초과 가게)의 이 분위 값이 같아지도록 랭킹 점수에 배율을 곱한다 —
	 * 운영 09-18: 행동 p95 5 · 랭킹 p95 49 → 배율 0.102. 랭킹은 6월 스냅샷이라 행동이 쌓일수록 자연히 비중이 준다.
	 */
	@DecimalMin("0.5")
	@DecimalMax("1.0")
	private double rankingScaleQuantile = 0.95;
}
