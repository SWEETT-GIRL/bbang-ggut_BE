package sweetgirl.croissant.global.logging;

import java.util.regex.Pattern;

/**
 * URL 에 실리는 토큰을 로그에 남기지 않게 가린다.
 *
 * 초대 토큰(/courses/invites/{token} — 수락하면 편집자가 된다)과 공유 토큰(/courses/by-share/{token}, ?t= — 보기만)이
 * 경로·쿼리스트링에 실린다. 요청 로그(request_logs 테이블·API_PERF 라인·CommonsRequestLoggingFilter)가
 * URI 를 그대로 남기면 로그를 볼 수 있는 사람이 남의 코스에 들어갈 수 있다.
 */
public final class SensitiveUrlMasker {

	private static final String MASK = "***";
	private static final Pattern TOKEN_PATH = Pattern.compile("(/courses/(?:invites|by-share)/)[A-Za-z0-9_-]+");
	private static final Pattern TOKEN_QUERY = Pattern.compile("(^|[?&])t=[^&;\\s]*");

	private SensitiveUrlMasker() {

	}

	/** 경로의 토큰 세그먼트를 가린다. 뒤에 붙는 세그먼트(/accept)는 그대로다. */
	public static String maskPath(String uri) {
		if (uri == null) {
			return null;
		}
		return TOKEN_PATH.matcher(uri).replaceAll("$1" + MASK);
	}

	/** 쿼리스트링의 t= 값을 가린다. 다른 파라미터는 그대로다. */
	public static String maskQuery(String queryString) {
		if (queryString == null) {
			return null;
		}
		return TOKEN_QUERY.matcher(queryString).replaceAll("$1t=" + MASK);
	}

	/** "uri=/courses/1?t=abc;client=..." 처럼 URI 와 쿼리가 한 문자열에 섞인 로그 메시지용. */
	public static String maskUrl(String text) {
		return maskQuery(maskPath(text));
	}
}
