package sweetgirl.croissant.external.llm.exception;

import lombok.Getter;
import sweetgirl.croissant.global.exception.CustomException;

@Getter
public class LlmException extends CustomException {

	private final LlmErrorCode llmErrorCode;

	public LlmException(LlmErrorCode errorCode) {
		super(errorCode);
		this.llmErrorCode = errorCode;
	}

	public LlmException(LlmErrorCode errorCode, Throwable cause) {
		super(errorCode, cause);
		this.llmErrorCode = errorCode;
	}

	/** 잠시 뒤 같은 요청을 다시 보내면 될 종류인가. */
	public boolean isRetryable() {
		return llmErrorCode == LlmErrorCode.RATE_LIMITED || llmErrorCode == LlmErrorCode.UNAVAILABLE;
	}
}
