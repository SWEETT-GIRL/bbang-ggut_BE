package sweetgirl.croissant.world.code;

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
public enum WorldErrorCode implements BaseErrorCode {

	WORLD_CHARACTER_NOT_FOUND(NOT_FOUND, "WORLD404_1",
			"그런 친구가 없습니다."),
	WORLD_CHARACTER_LOCKED(FORBIDDEN, "WORLD403_1",
			"아직 만나지 못한 친구입니다."),
	WORLD_PLANET_NOT_FOUND(NOT_FOUND, "WORLD404_2",
			"그런 행성이 없습니다."),
	WORLD_PLANET_NOT_REACHED(FORBIDDEN, "WORLD403_2",
			"아직 닿지 않은 행성입니다."),
	WORLD_ROULETTE_NOT_AVAILABLE(BAD_REQUEST, "WORLD400_1",
			"이 행성에는 돌릴 룰렛이 없습니다."),
	WORLD_ROULETTE_REQUIRED(FORBIDDEN, "WORLD403_3",
			"먼저 이 행성의 룰렛을 돌려야 합니다."),
	WORLD_REFERRAL_NOT_ALLOWED(BAD_REQUEST, "WORLD400_2",
			"이 행성에서는 친구 추천으로 깨울 수 없습니다."),
	WORLD_ROULETTE_ALREADY_DONE(CONFLICT, "WORLD409_1",
			"이미 룰렛을 돌린 행성입니다."),
	WORLD_NO_SLEEPING_FRIEND(CONFLICT, "WORLD409_2",
			"이 행성에서 만날 친구가 남아 있지 않습니다."),
	WORLD_CHARACTER_ALREADY_AWAKE(CONFLICT, "WORLD409_3",
			"이미 깨어난 친구입니다."),
	WORLD_NO_WAKE_TICKET(CONFLICT, "WORLD409_4",
			"쓸 수 있는 깨우기 티켓이 없습니다."),
	WORLD_PLANET_CODE_CONFLICT(CONFLICT, "WORLD409_5",
			"이미 쓰고 있는 행성 코드입니다."),
	WORLD_PLANET_INVALID(BAD_REQUEST, "WORLD400_3",
			"행성 값이 올바르지 않습니다."),
	WORLD_STAGE_NOT_FOUND(NOT_FOUND, "WORLD404_3",
			"그런 고도 단계가 없습니다."),
	WORLD_STAGE_INVALID(BAD_REQUEST, "WORLD400_4",
			"고도 단계 값이 올바르지 않습니다."),
	WORLD_CATALOG_NOT_INITIALIZED(INTERNAL_SERVER_ERROR, "WORLD500_1",
			"세계관 카탈로그가 준비되지 않았습니다."),
	WORLD_MASCOT_NOT_CONFIGURED(INTERNAL_SERVER_ERROR, "WORLD500_2",
			"첫 친구로 지급할 캐릭터가 지정되지 않았습니다."),
	WORLD_NOT_STARTED(INTERNAL_SERVER_ERROR, "WORLD500_3",
			"세계관 여정이 아직 시작되지 않았습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
