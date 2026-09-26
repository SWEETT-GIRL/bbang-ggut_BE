package sweetgirl.croissant.coupon.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum CouponErrorCode implements BaseErrorCode {

	COUPON_NOT_FOUND(NOT_FOUND, "COUPON_NOT_FOUND", "쿠폰을 찾을 수 없습니다."),
	COUPON_NOT_OPENED(BAD_REQUEST, "COUPON_NOT_OPENED", "아직 발급이 시작되지 않은 쿠폰입니다."),
	COUPON_SOLD_OUT(CONFLICT, "COUPON_SOLD_OUT", "쿠폰이 모두 소진되었습니다."),
	COUPON_ALREADY_ISSUED(CONFLICT, "COUPON_ALREADY_ISSUED", "이미 발급받은 쿠폰입니다."),
	/** 락 대기가 타임아웃됐다. 서버가 붐빈다는 뜻이지 쿠폰이 없다는 뜻이 아니다 — 소진(409)과 구분해서 센다. */
	COUPON_ISSUE_BUSY(SERVICE_UNAVAILABLE, "COUPON_ISSUE_BUSY", "발급 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
