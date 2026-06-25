package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseUpdateRequest {

	@Size(max = 100, message = "코스 제목은 100자 이내여야 합니다.")
	private String title;

	private Boolean isPublic;
}
