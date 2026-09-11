package sweetgirl.croissant.recommendation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 상권의 동선 기준. 취향 후보가 부족할 때 반경을 넓힐지는 이 값이 정한다 (설계 §1 "취향 후보 부족"). */
@Getter
@RequiredArgsConstructor
public enum CourseAreaScale {

	WALK("도보 상권. 반경을 확장하지 않고 tasteFit 규칙으로 정직하게 보여준다"),
	CAR("차량 상권. 취향 후보가 3곳 미만이면 반경을 1.5배씩 2번 확장한다");

	private final String description;
}
