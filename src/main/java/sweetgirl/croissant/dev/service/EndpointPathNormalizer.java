package sweetgirl.croissant.dev.service;

import java.util.regex.Pattern;

import sweetgirl.croissant.global.logging.SensitiveUrlMasker;

/**
 * request_log 의 경로를 통계용 키로 접는다 — {@code /stores/123} 과 {@code /stores/456} 은 같은 엔드포인트다.
 * 숫자 세그먼트는 {@code {id}} 로, 초대·공유 토큰은 {@link SensitiveUrlMasker} 가 가리는 값(***) 으로 모인다.
 */
public final class EndpointPathNormalizer {

	private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+(?=/|$)");
	private static final String ID_PLACEHOLDER = "/{id}";

	private EndpointPathNormalizer() {

	}

	public static String normalize(String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			return "(none)";
		}
		String masked = SensitiveUrlMasker.maskPath(endpoint);
		return NUMERIC_SEGMENT.matcher(masked).replaceAll(ID_PLACEHOLDER);
	}
}
