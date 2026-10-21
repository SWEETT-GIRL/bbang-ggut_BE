package sweetgirl.croissant.store.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 자동완성 Redis 캐시의 손잡이. 캐시 동작은 {@code StoreAutocompleteCache}, 무효화는 {@code AutocompleteCacheInvalidator} 에 있다.
 *
 * <p>신선도는 TTL 이 아니라 가게 변경 이벤트의 정밀 무효화가 맡는다. TTL 은 무효화가 빠졌을 때의 안전장치라 길게 둔다 — 운영 로그
 * (08-28~10-02) 시뮬레이션에서 적중률이 1시간 16% · 1일 21% · 7일 31% 로 TTL 을 늘릴수록 계속 올랐다.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "store.search.autocomplete-cache")
public class StoreAutocompleteCacheProperties {

	/** 끄면 매번 DB 를 조회한다(Redis 가 없는 환경의 기본값) */
	private boolean enabled = false;

	@NotNull
	private Duration ttl = Duration.ofDays(7);

	/**
	 * TTL 을 ±이 비율만큼 흔든다 — 같은 시간대에 채워진 키가 한꺼번에 만료돼 DB 로 몰리는 것(애벌랜치)을 흩는다.
	 */
	@DecimalMin("0.0")
	@DecimalMax("0.5")
	private double ttlJitterRatio = 0.1;

	/** 캐시가 빈 검색어를 DB 에서 채우는 한 요청만 잡는 락의 수명 — 그 요청이 죽어도 이 시간 뒤엔 풀린다 */
	@NotNull
	private Duration lockTtl = Duration.ofSeconds(3);

	/** 락을 못 잡은 요청이 다시 읽기 전에 기다리는 시간 */
	@NotNull
	private Duration lockWait = Duration.ofMillis(50);

	/** 락을 못 잡은 요청이 다시 읽는 횟수 — 다 써도 비어 있으면 직접 DB 를 조회한다 */
	@Min(0)
	private int lockRetries = 3;
}
