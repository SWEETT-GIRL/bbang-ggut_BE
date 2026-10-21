package sweetgirl.croissant.course.dto.response;

import sweetgirl.croissant.course.enums.CourseVisibility;

/** 코스 수정 응답. 공유 토큰은 방장에게만, 링크 공개(UNLISTED)일 때만 실린다 — 멤버가 제목만 고쳐도 이 응답을 받는다. */
public record CourseUpdateResponse(
		CourseVisibility visibility,
		String shareToken) {
}
