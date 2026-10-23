package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

/** 정류장에 붙은 한 사람의 메모. 이름은 코스 안에서 쓰는 이름(course_members)이고, 행이 없는 옛 데이터는 닉네임이다. */
public record CourseStoreMemoResponse(
		Long memberId,
		String displayName,
		String content,
		LocalDateTime updatedAt,
		boolean isMine,
		/** 메모 쓴 사람 얼굴(10/22 추가) — 세계관이 없으면 null → 앱은 이니셜 */
		String characterCode,
		String characterImageKey) {

	public CourseStoreMemoResponse(Long memberId, String displayName, String content, LocalDateTime updatedAt,
			boolean isMine) {
		this(memberId, displayName, content, updatedAt, isMine, null, null);
	}
}
