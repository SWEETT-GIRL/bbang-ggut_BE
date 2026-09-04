package sweetgirl.croissant.course.dto.response;

/** 코스 상세의 참여자 한 명. 참여 순서(course_members.joined_at)대로 내려간다 — 방장은 첫 초대 때 행이 생기므로 맨 앞. */
public record CourseMemberResponse(
		Long memberId,
		String displayName,
		boolean isOwner,
		boolean isMe) {
}
