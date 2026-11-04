package sweetgirl.croissant.course.dto.request;

import java.time.LocalTime;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.course.entity.CourseStoreMemo;

@Getter
@NoArgsConstructor
public class CourseStoreUpdateRequest {

	/** 내 메모. 빈 값이면 내 메모를 지운다. 방문계획 메모와 같은 200자 제한. */
	@Size(max = CourseStoreMemo.CONTENT_MAX_LENGTH, message = "메모는 {max}자 이내여야 합니다.")
	private String memo;

	/**
	 * 메모의 순서 기준(선택). 초안 자동저장을 쓰는 앱이 보내는 하이브리드 논리 시계 — 초안으로 먼저 간 더 새 글을 이 PATCH 가
	 * 덮어쓰지 않게 한다. 안 보내면(구버전 앱) 서버 시각(ms)을 쓴다.
	 */
	@Positive(message = "memoSeq 는 1 이상이어야 합니다.")
	private Long memoSeq;

	private LocalTime visitTime;
}
