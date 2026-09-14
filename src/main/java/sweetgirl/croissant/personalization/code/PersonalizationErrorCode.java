package sweetgirl.croissant.personalization.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

/**
 * 개인화 모듈의 에러 코드.
 *
 * 400 은 검사 응답처럼 호출자가 고칠 수 있는 입력 오류다.
 * 500 은 축과 행동 신호에 대한 서버 안의 약속이 깨진 것이다 — 배치나 서비스가
 * 앞 단계 검사를 빼먹은 버그라서 사용자에게 원인을 돌리지 않는다.
 */
@Getter
@AllArgsConstructor
public enum PersonalizationErrorCode implements BaseErrorCode {

	QUIZ_ANSWERS_INVALID(HttpStatus.BAD_REQUEST, "PERSONALIZATION400_1",
			"검사 응답은 네 축에 하나씩(4문항) 또는 둘씩(8문항) 있어야 합니다."),

	INTERACTION_TARGET_REQUIRED(HttpStatus.INTERNAL_SERVER_ERROR, "PERSONALIZATION500_1",
			"가게나 메뉴 중 하나는 있어야 하는 신호입니다."),
	TASTE_AXIS_INCOMPLETE(HttpStatus.INTERNAL_SERVER_ERROR, "PERSONALIZATION500_2",
			"네 축이 모두 있어야 타입을 정할 수 있습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
