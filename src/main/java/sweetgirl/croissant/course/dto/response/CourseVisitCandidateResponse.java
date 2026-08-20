package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.breadlog.entity.BreadRecord;

/**
 * 코스에서 "다녀왔어요"를 누를 때 고를 수 있는 기록 하나.
 *
 * 날짜 제한 없이 그 가게의 내 기록을 전부 내려준다 — 예전 기록을 고르는 것도
 * 사용자의 선택이므로 막지 않는다. 대신 화면이 언제 먹은 건지 보여줄 수 있게
 * eatenAt 을 함께 준다.
 */
public record CourseVisitCandidateResponse(
		Long recordId,
		LocalDateTime eatenAt,
		String memo,
		boolean linked) {

	public static CourseVisitCandidateResponse of(BreadRecord record, boolean linked) {
		return new CourseVisitCandidateResponse(
			record.getId(),
			record.getEatenAt(),
			record.getMemo(),
			linked);
	}
}
