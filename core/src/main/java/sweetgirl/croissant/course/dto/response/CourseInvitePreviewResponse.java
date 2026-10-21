package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.enums.CourseInviteState;

/**
 * 수락 화면·랜딩용 미리보기. 가게 목록·메모는 넣지 않는다 — 토큰을 아는 사람은 아직 멤버가 아니다.
 * state 가 INVALID 면 코스 정보는 전부 null 이다.
 */
public record CourseInvitePreviewResponse(
		CourseInviteState state,
		Long courseId,
		String title,
		/** 방장 닉네임. */
		String ownerName,
		/** 방장 포함 활성 멤버 수. */
		int memberCount,
		int memberLimit,
		LocalDateTime expiresAt,
		/** 인증 헤더가 있을 때만 채운다. 앱이 "이미 멤버·방장이면 바로 코스로" 보내는 데 쓴다. */
		Viewer viewer) {

	public record Viewer(boolean isMember, boolean isOwner) {
	}

	public static CourseInvitePreviewResponse invalid() {
		return new CourseInvitePreviewResponse(
			CourseInviteState.INVALID, null, null, null, 0, CourseMember.MEMBER_LIMIT, null, null);
	}

	public static CourseInvitePreviewResponse of(
			CourseInviteState state,
			Course course,
			long memberCount,
			LocalDateTime expiresAt,
			Viewer viewer) {
		String ownerName = course.getMember() != null ? course.getMember().getNickname() : null;
		return new CourseInvitePreviewResponse(
			state, course.getId(), course.getTitle(), ownerName,
			(int)memberCount, CourseMember.MEMBER_LIMIT, expiresAt, viewer);
	}
}
