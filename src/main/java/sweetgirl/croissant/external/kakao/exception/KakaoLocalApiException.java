package sweetgirl.croissant.external.kakao.exception;

import sweetgirl.croissant.global.exception.CustomException;

public class KakaoLocalApiException extends CustomException {

	public KakaoLocalApiException(KakaoErrorCode errorCode, Throwable cause) {
		super(errorCode, cause);
	}
}
