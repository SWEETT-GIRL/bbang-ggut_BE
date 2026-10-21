package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AdminCourseUpdateRequest {

	@Size(max = 100, message = "코스 제목은 100자 이내여야 합니다.")
	private String title;

	@Size(max = 30, message = "뱃지는 30자 이내여야 합니다.")
	private String badge;

	/** 탐색 탭 필터 축 이름("대학가"). category 와 한 쌍으로만 바뀐다. 둘 다 빈 문자열이면 분류를 뗀다. */
	@Size(max = 20, message = "분류 그룹은 20자 이내여야 합니다.")
	private String categoryGroup;

	/** 그 축 안의 값("홍대"). */
	@Size(max = 20, message = "분류는 20자 이내여야 합니다.")
	private String category;
}
