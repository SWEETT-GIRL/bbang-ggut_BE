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
		String characterImageKey,
		/** 순서 기준(11/3 추가). 앱이 초안·로컬 글과 비교해 가장 큰 것을 그리고, 내 seq 가 DB 에 닿았는지 확인한다. 옛 행은 0 */
		long seq) {
}
