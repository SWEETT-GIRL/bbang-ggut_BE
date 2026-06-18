package sweetgirl.croissant.course.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CourseType {

	CURATED("큐레이션"),
	CUSTOM("커스텀"),;

	private final String description;
}
