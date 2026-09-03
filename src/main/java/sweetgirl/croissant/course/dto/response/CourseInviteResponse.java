package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

/** 초대 링크. URL({SITE_BASE}/i/{token})은 앱이 조립한다 — 서버는 도메인을 모른다. */
public record CourseInviteResponse(
		String token,
		LocalDateTime expiresAt,
		/** 살아있는 링크를 그대로 돌려줬는가. */
		boolean reused) {
}
