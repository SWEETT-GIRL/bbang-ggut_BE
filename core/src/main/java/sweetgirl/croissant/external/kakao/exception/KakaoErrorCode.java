package sweetgirl.croissant.external.kakao.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum KakaoErrorCode implements BaseErrorCode {

	KEYWORD_SEARCH_ERROR(HttpStatus.BAD_GATEWAY, "KAKAO502_1", "카카오 키워드 검색 API 호출에 실패했습니다."),
	COORD_TO_REGION_ERROR(HttpStatus.BAD_GATEWAY, "KAKAO502_2", "카카오 좌표 변환 API 호출에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
