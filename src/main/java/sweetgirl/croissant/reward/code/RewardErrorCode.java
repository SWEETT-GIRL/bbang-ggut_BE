package sweetgirl.croissant.reward.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
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
	REWARD_SHOP_POSE_UNAVAILABLE(BAD_REQUEST, "REWARD400_2",
			"이 친구에게는 없는 모습입니다."),
	REWARD_SHOP_ITEM_NOT_OWNED(BAD_REQUEST, "REWARD400_3",
			"가지고 있지 않은 품목입니다."),
	REWARD_SHOP_CHARACTER_REQUIRED(BAD_REQUEST, "REWARD400_4",
			"모습은 어느 친구의 것인지 골라야 합니다."),
	REWARD_FRIEND_NICKNAME_INVALID(BAD_REQUEST, "REWARD400_5",
			"친구 이름은 8자까지입니다."),
	REWARD_INVITE_TOO_LATE(BAD_REQUEST, "REWARD400_6",
			"초대 코드는 가입한 지 7일 안, 첫 빵을 기록하기 전에만 넣을 수 있습니다."),
	REWARD_INVITE_OWN_CODE(BAD_REQUEST, "REWARD400_7",
			"내 초대 코드는 넣을 수 없습니다."),
	REWARD_INVITE_MUTUAL(BAD_REQUEST, "REWARD400_8",
			"내가 초대한 친구의 코드는 넣을 수 없습니다."),
	REWARD_SHOP_LEVEL_LOCKED(FORBIDDEN, "REWARD403_1",
			"아직 살 수 없는 품목입니다."),
	REWARD_INVITE_REJOINED(FORBIDDEN, "REWARD403_2",
			"다시 가입한 회원은 초대 코드를 넣을 수 없습니다."),
	REWARD_SETTING_NOT_FOUND(NOT_FOUND, "REWARD404_1",
			"그런 보상 설정이 없습니다."),
	REWARD_LETTER_NOT_FOUND(NOT_FOUND, "REWARD404_2",
			"그런 편지가 없습니다."),
	REWARD_SHOP_ITEM_NOT_FOUND(NOT_FOUND, "REWARD404_3",
			"그런 품목이 없습니다."),
	REWARD_INVITE_CODE_NOT_FOUND(NOT_FOUND, "REWARD404_4",
			"그런 초대 코드가 없습니다."),
	REWARD_CRUMBS_INSUFFICIENT(CONFLICT, "REWARD409_1",
			"부스러기가 부족합니다."),
	REWARD_SHOP_ALREADY_OWNED(CONFLICT, "REWARD409_2",
			"이미 가지고 있는 품목입니다."),
	REWARD_WAKE_AD_STARTED(CONFLICT, "REWARD409_3",
			"광고로 깨우던 친구는 광고로만 깨울 수 있습니다."),
	REWARD_INVITE_ALREADY_ACCEPTED(CONFLICT, "REWARD409_4",
			"이미 초대 코드를 넣었습니다."),
	REWARD_INVITE_CODE_UNAVAILABLE(INTERNAL_SERVER_ERROR, "REWARD500_2",
			"초대 코드를 만들지 못했습니다. 잠시 뒤 다시 시도해 주세요."),
	REWARD_PENDING_PAYLOAD_INVALID(INTERNAL_SERVER_ERROR, "REWARD500_1",
			"보상 연출 정보를 읽지 못했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
