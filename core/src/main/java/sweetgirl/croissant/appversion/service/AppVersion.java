package sweetgirl.croissant.appversion.service;

import java.util.regex.Pattern;

/** "1.2.3" 세 자리 버전 — 어드민 입력 검증, min ≤ latest 비교, 강제 업데이트 기준 비교(커뮤니티 규칙 동의 문)에 쓴다. */
public final class AppVersion {

	private static final Pattern FORMAT = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

	private AppVersion() {

	}

	public static boolean isValid(String version) {
		return version != null && FORMAT.matcher(version).matches();
	}

	/** 둘 다 유효한 형식이라고 가정한다 */
	public static int compare(String left, String right) {
		String[] leftParts = left.split("\\.");
		String[] rightParts = right.split("\\.");
		for (int i = 0; i < 3; i++) {
			int diff = Integer.compare(Integer.parseInt(leftParts[i]), Integer.parseInt(rightParts[i]));
			if (diff != 0) {
				return diff;
			}
		}
		return 0;
	}
}
