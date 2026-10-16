package sweetgirl.croissant.global.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code ops.rate-limit.*} — IP 당 {@code window} 동안 허용하는 요청 수. 0 이하면 그 묶음은 막지 않는다.
 *
 * <p>켜고 끄는 스위치는 {@code ops.rate-limit.enabled}(RateLimitFilterConfig 가 본다). 자동완성은 글자를 칠 때마다 부르므로 가장 넉넉하다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ops.rate-limit")
public class RateLimitProperties {

	private Duration window = Duration.ofMinutes(1);
	private long maxTrackedKeys = 100_000;
	private int guestToken = 20;
	private int authLogin = 30;
	private int authRefresh = 60;
	private int storeSearch = 120;
	private int storeAutocomplete = 300;

	Map<RateLimitRule, Integer> limits() {
		Map<RateLimitRule, Integer> limits = new EnumMap<>(RateLimitRule.class);
		limits.put(RateLimitRule.GUEST_TOKEN, guestToken);
		limits.put(RateLimitRule.AUTH_LOGIN, authLogin);
		limits.put(RateLimitRule.AUTH_REFRESH, authRefresh);
		limits.put(RateLimitRule.STORE_SEARCH, storeSearch);
		limits.put(RateLimitRule.STORE_AUTOCOMPLETE, storeAutocomplete);
		return limits;
	}
}
