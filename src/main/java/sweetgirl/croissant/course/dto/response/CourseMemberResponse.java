package sweetgirl.croissant.course.dto.response;

/** 코스 상세의 참여자 한 명. 참여 순서(course_members.joined_at)대로 내려간다 — 방장은 첫 초대 때 행이 생기므로 맨 앞. */
public record CourseMemberResponse(
		Long memberId,
		String displayName,
		boolean isOwner,
		boolean isMe,
		/** 일행 얼굴(10/22 추가) — 세계관이 없으면 null → 앱은 마스코트 */
		String characterCode,
		String characterImageKey) {

	public CourseMemberResponse(Long memberId, String displayName, boolean isOwner, boolean isMe) {
		this(memberId, displayName, isOwner, isMe, null, null);
	}
}
