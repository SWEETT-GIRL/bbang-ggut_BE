package sweetgirl.croissant.banner.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum BannerErrorCode implements BaseErrorCode {

	BANNER_NOT_FOUND(NOT_FOUND, "BANNER404_1", "배너를 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
