package sweetgirl.croissant.coupon.exception;

import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class CouponException extends CustomException {

	public CouponException(CouponErrorCode errorCode) {
		super(errorCode);
	}

	public CouponException(CouponErrorCode errorCode, Throwable cause) {
		super(errorCode, cause);
	}
}
