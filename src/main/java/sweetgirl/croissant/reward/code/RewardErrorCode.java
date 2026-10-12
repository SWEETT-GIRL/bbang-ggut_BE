package sweetgirl.croissant.reward.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum RewardErrorCode implements BaseErrorCode {

	REWARD_AMOUNT_INVALID(BAD_REQUEST, "REWARD400_1",
			"부스러기 수량이 올바르지 않습니다."),
	REWARD_SETTING_NOT_FOUND(NOT_FOUND, "REWARD404_1",
			"그런 보상 설정이 없습니다."),
	REWARD_CRUMBS_INSUFFICIENT(CONFLICT, "REWARD409_1",
			"부스러기가 부족합니다."),
	REWARD_PENDING_PAYLOAD_INVALID(INTERNAL_SERVER_ERROR, "REWARD500_1",
			"보상 연출 정보를 읽지 못했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
