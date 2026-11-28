package sweetgirl.dataops.autoapply;

import org.springframework.http.HttpStatus;
import sweetgirl.dataops.global.code.BaseErrorCode;
import sweetgirl.dataops.global.exception.CustomException;

/** 보류 사유는 저장된 결과와 재전달 응답에서 사용하는 안정적인 코드다. */
public abstract class AutomaticApplyException extends CustomException {
	private AutomaticApplyException(Reason reason) { super(reason); }

	public static final class Hold extends AutomaticApplyException {
		public Hold(Reason reason) { super(reason); }
	}

	public enum Reason implements BaseErrorCode {
		AGENT_APPROVAL_REQUIRED,
		AUTOMATIC_SCOPE_REQUIRED,
		OBSERVATION_UNUSABLE,
		STORE_INACTIVE,
		STORE_MISSING,
		CANDIDATE_CONFLICT,
		CAPTURE_MISSING,
		CATEGORY_EVIDENCE_REQUIRED,
		DELETION_EVIDENCE_REQUIRES_REVIEW,
		FIELD_EVIDENCE_REQUIRED,
		INCOMPLETE_HOURS,
		INVALID_DESCRIPTION,
		INVALID_HOURS,
		INVALID_INSTAGRAM,
		INVALID_JSON,
		INVALID_MENU,
		INVALID_PHONE,
		INVALID_PRICE,
		LOCATION_EVIDENCE_REQUIRED,
		MENU_POLICY_REVIEW_REQUIRED,
		OBSERVATION_EXPIRED,
		OBSERVATION_HELD,
		POLICY_CHANGED,
		PROPOSAL_MISSING,
		PROTECTED_SOURCE,
		SOURCE_CONFLICT,
		SOURCE_UNKNOWN,
		SOURCE_UNSUPPORTED,
		STALE_OBSERVATION,
		STORE_IDENTITY_CHANGE,
		STORE_LOCATION_CHANGE,
		STRUCTURE_CHANGED;

		@Override public HttpStatus getHttpStatus() { return HttpStatus.CONFLICT; }
		@Override public String getCode() { return name(); }
		@Override public String getMessage() { return name(); }
	}
}
