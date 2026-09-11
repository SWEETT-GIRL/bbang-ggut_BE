package sweetgirl.croissant.course.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 코스가 어떻게 채워졌는지. null 이면 직접 만든 코스다. */
@Getter
@RequiredArgsConstructor
public enum CourseSource {

	RECOMMENDED("추천 결과를 bulk 담기로 채운 코스. courses.recommendation_id 가 같이 붙는다");

	private final String description;
}
