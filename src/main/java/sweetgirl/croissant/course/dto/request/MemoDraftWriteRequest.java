package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.course.entity.CourseStoreMemo;

/** 메모 초안 한 번(PUT memo-draft). 치는 대로 0.5초 디바운스로 온다. 빈 content 는 "지우기 예약". */
@Getter
@NoArgsConstructor
public class MemoDraftWriteRequest {

	@Size(max = CourseStoreMemo.CONTENT_MAX_LENGTH, message = "메모는 {max}자 이내여야 합니다.")
	private String content;

	/** 앱이 (가게, 회원) 필드마다 만드는 하이브리드 논리 시계 — max(직전 seq + 1, 서버에서 본 최대 seq + 1, 현재 ms). */
	@NotNull(message = "seq 는 필수입니다.")
	@Positive(message = "seq 는 1 이상이어야 합니다.")
	private Long seq;
}
