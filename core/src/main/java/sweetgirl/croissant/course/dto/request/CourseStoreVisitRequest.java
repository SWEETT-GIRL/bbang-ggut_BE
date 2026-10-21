package sweetgirl.croissant.course.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseStoreVisitRequest {

	@NotNull
	private Long breadRecordId;
}
