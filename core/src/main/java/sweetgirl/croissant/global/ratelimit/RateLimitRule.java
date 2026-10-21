package sweetgirl.croissant.global.ratelimit;

import java.util.Optional;

/** IP 당 횟수를 따로 세는 묶음. 묶음마다 문턱이 다르다({@link RateLimitProperties}). */
public enum RateLimitRule {

	GUEST_TOKEN,
	AUTH_LOGIN,
	AUTH_REFRESH,
	STORE_SEARCH,
	STORE_AUTOCOMPLETE;

	/** 대상이 아니면 비어 있다 — 그대로 통과시킨다는 뜻이다. */
	static Optional<RateLimitRule> match(String method, String path) {
		return Optional.ofNullable(resolve(method, path));
	}

	private static RateLimitRule resolve(String method, String path) {
		if ("POST".equals(method)) {
			return switch (path) {
				case "/auth/guest" -> GUEST_TOKEN;
				case "/auth/kakao", "/auth/apple" -> AUTH_LOGIN;
				case "/auth/refresh" -> AUTH_REFRESH;
				default -> null;
			};
		}
		if ("GET".equals(method)) {
			return switch (path) {
				case "/stores/search" -> STORE_SEARCH;
				case "/stores/autocomplete" -> STORE_AUTOCOMPLETE;
				default -> null;
			};
		}
		return null;
	}
}
