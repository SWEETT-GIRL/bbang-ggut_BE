package sweetgirl.croissant.recommendation.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

/**
 * 추천 모듈의 에러 코드.
 *
 * 400 은 호출자가 고칠 수 있는 입력 오류, 404 는 없는(또는 내 것이 아닌) 추천·상권이다 — 남의 추천은 존재를 알리지 않으려고 404 다.
 * 축·행동 신호의 서버 약속 위반은 PersonalizationErrorCode 가 맡는다.
 */
@Getter
@AllArgsConstructor
public enum RecommendationErrorCode implements BaseErrorCode {

	COURSE_AREA_RADIUS_INVALID(HttpStatus.BAD_REQUEST, "RECOMMENDATION400_1",
			"상권 반경과 구간 상한은 양수여야 합니다."),
	COURSE_AREA_REGION_INVALID(HttpStatus.BAD_REQUEST, "RECOMMENDATION400_2",
			"읍면동만으로는 상권 소속을 정할 수 없습니다. 시군구가 함께 필요합니다."),
	FEEDBACK_STORE_REQUIRED(HttpStatus.BAD_REQUEST, "RECOMMENDATION400_3",
			"가게 단위 반응에는 가게 ID가 필요합니다."),
	FEEDBACK_REASON_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "RECOMMENDATION400_4",
			"이유는 별로예요 반응에만 붙일 수 있습니다."),
	ANCHOR_STORE_NOT_IN_COURSE(HttpStatus.BAD_REQUEST, "RECOMMENDATION400_5",
			"기준 가게가 이 코스에 담겨 있지 않습니다."),
	STORE_COUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "RECOMMENDATION400_6",
			"추천 가게 수는 1곳 이상 6곳 이하여야 합니다."),

	RECOMMENDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION404_1", "추천을 찾을 수 없습니다."),
	COURSE_AREA_NOT_FOUND(HttpStatus.NOT_FOUND, "RECOMMENDATION404_2", "이 코스 지역의 상권을 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
