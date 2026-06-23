package sweetgirl.croissant.course.dto.request;

import java.time.LocalTime;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CourseStoreUpdateRequest {

	private String memo;

	private LocalTime visitTime;
}
