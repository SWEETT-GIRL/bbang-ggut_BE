package sweetgirl.croissant.banner.exception;

import sweetgirl.croissant.banner.code.BannerErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class BannerException extends CustomException {

	public BannerException(BannerErrorCode errorCode) {
		super(errorCode);
	}
}
