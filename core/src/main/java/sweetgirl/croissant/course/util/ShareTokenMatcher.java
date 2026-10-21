package sweetgirl.croissant.course.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseVisibility;

/**
 * 링크 공개(UNLISTED) 코스의 공유 토큰 판정. 상세·폴링·포크가 같은 판정을 쓴다.
 *
 * PRIVATE 은 토큰이 맞아도 false — 토큰보다 PRIVATE 이 강하다. PUBLIC 은 이 판정을 타지 않는다(누구나 본다).
 * 비교는 상수 시간(MessageDigest.isEqual)이다 — String.equals 는 앞자리부터 끊겨 길이·접두사가 샌다.
 */
public final class ShareTokenMatcher {

	private ShareTokenMatcher() {

	}

	public static boolean matches(Course course, String presentedToken) {
		if (course.getVisibility() != CourseVisibility.UNLISTED) {
			return false;
		}
		String issuedToken = course.getShareToken();
		if (issuedToken == null || presentedToken == null) {
			return false;
		}
		return MessageDigest.isEqual(
			issuedToken.getBytes(StandardCharsets.UTF_8),
			presentedToken.getBytes(StandardCharsets.UTF_8));
	}
}
