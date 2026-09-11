package sweetgirl.croissant.recommendation.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

/**
 * 추천 모듈의 에러 코드.
 *
 * 지금은 400 만 있다. 호출자가 고칠 수 있는 입력 오류다(상권 등록 값, 피드백 요청 본문).
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
			"가게 단위 반응에는 가게 ID가 필요합니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
