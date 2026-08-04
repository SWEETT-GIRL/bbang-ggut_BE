package sweetgirl.croissant.mission.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MissionErrorCode implements BaseErrorCode {

	MISSION_NOT_FOUND(NOT_FOUND, "MISSION404_1", "미션을 찾을 수 없습니다."),
	MISSION_CODE_CONFLICT(CONFLICT, "MISSION409_1", "이미 사용 중인 미션 코드입니다."),
	INVALID_MISSION_CONDITION(BAD_REQUEST, "MISSION400_1", "미션 조건과 threshold가 올바르지 않습니다."),
	BADGE_NOT_FOUND(BAD_REQUEST, "MISSION400_2", "연결할 뱃지를 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
