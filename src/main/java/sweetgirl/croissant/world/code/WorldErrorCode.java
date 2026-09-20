package sweetgirl.croissant.world.code;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum WorldErrorCode implements BaseErrorCode {

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
