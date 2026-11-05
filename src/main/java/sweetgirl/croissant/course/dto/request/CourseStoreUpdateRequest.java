package sweetgirl.croissant.course.dto.request;

import java.time.LocalTime;

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

	private LocalTime visitTime;
}
