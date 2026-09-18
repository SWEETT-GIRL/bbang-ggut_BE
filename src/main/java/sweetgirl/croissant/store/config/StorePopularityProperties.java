package sweetgirl.croissant.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 가게 인기도(store.popularity_score) 산식의 손잡이 (#663 ③). 산식 자체는 {@code StorePopularityRepository} 에 있다.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "store.popularity")
public class StorePopularityProperties {

	/**
	 * 상대 전환율의 스무딩 표본 수 k: ((저장 + 담기) + k·p0) ÷ (조회 회원 + k) ÷ p0. 조회 회원이 k 보다 훨씬 적은 가게는 전체 평균
	 * p0 쪽으로 끌려가(비율 ≈ 1) 3명이 본 가게의 100% 전환이 300명이 본 가게의 50% 를 이기지 않는다. 운영 실측(09-18) 기준 10.
	 */
	@Min(0)
	private int conversionSmoothing = 10;
}
