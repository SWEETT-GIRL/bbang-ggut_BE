package sweetgirl.croissant.recommendation.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

/**
 * 추천 모듈의 에러 코드.
 *
 * 400 은 호출자가 고칠 수 있는 입력 오류다(상권 등록 값, 피드백 요청 본문).
 * 500 은 서버 안에서 지켜져야 할 약속이 깨진 것이다 — 배치나 서비스가 앞 단계 검사를 빼먹은 버그라서
 * 사용자에게 원인을 돌리지 않는다.
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

	INTERACTION_TARGET_REQUIRED(HttpStatus.INTERNAL_SERVER_ERROR, "RECOMMENDATION500_1",
			"가게나 메뉴 중 하나는 있어야 하는 신호입니다."),
	TASTE_AXIS_INCOMPLETE(HttpStatus.INTERNAL_SERVER_ERROR, "RECOMMENDATION500_2",
			"네 축이 모두 있어야 타입을 정할 수 있습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
