package sweetgirl.croissant.external.naver.exception;

import lombok.Getter;
import sweetgirl.croissant.global.exception.CustomException;

@Getter
public class NaverSearchException extends CustomException {

	private final NaverSearchErrorCode naverSearchErrorCode;

	public NaverSearchException(NaverSearchErrorCode errorCode) {
		super(errorCode);
		this.naverSearchErrorCode = errorCode;
	}

	public NaverSearchException(NaverSearchErrorCode errorCode, Throwable cause) {
		super(errorCode, cause);
		this.naverSearchErrorCode = errorCode;
	}

	public boolean isRateLimited() {
		return naverSearchErrorCode == NaverSearchErrorCode.RATE_LIMITED;
	}
}
