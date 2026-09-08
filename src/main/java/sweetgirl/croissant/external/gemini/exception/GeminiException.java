package sweetgirl.croissant.external.gemini.exception;

import lombok.Getter;
import sweetgirl.croissant.global.exception.CustomException;

@Getter
public class GeminiException extends CustomException {

	private final GeminiErrorCode geminiErrorCode;

	public GeminiException(GeminiErrorCode errorCode) {
		super(errorCode);
		this.geminiErrorCode = errorCode;
	}

	public GeminiException(GeminiErrorCode errorCode, Throwable cause) {
		super(errorCode, cause);
		this.geminiErrorCode = errorCode;
	}

	/** 잠시 뒤 같은 요청을 다시 보내면 될 종류인가 — 한도 초과·일시 장애 */
	public boolean isRetryable() {
		return geminiErrorCode == GeminiErrorCode.RATE_LIMITED
			|| geminiErrorCode == GeminiErrorCode.UNAVAILABLE;
	}
}
