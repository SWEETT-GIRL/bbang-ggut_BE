package sweetgirl.croissant.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

/** 가게 검색 관련도의 손잡이. 텍스트 점수 계단은 {@code StoreSearchSqlBuilder} 상수, 배포 없이 조정할 값만 여기 둔다. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "store.search")
public class StoreSearchProperties {

	/**
	 * 관련도 배수 (1 + factor × ln(1 + popularity_score)) 의 계수. 인기도 산식이 사람 수 눈금으로 바뀌면서(#663 ③, 상위 10% 경계 46 →
	 * 4.3) 0.3 그대로면 인기 있는 가게의 검색 비중이 줄어, 상위 10% 경계에서 예전 배수(2.15)가 유지되도록 0.68 로 올렸다.
	 */
	@PositiveOrZero
	private double popularityFactor = 0.68;

	/**
	 * 검색·자동완성 SQL 의 statement 타임아웃(초). 운영 EXPLAIN 이 1글자 자동완성도 6ms 라 2초는 수백 배 여유다 — 이걸 넘는 건
	 * 쿼리가 아니라 DB·커넥션이 멈춘 것이니, 요청 스레드를 붙잡아 두는 대신 끊고 자동완성은 빈 목록, 검색은 503 으로 답한다.
	 */
	@Positive
	private int queryTimeoutSeconds = 2;
}
