package sweetgirl.dataops.proposal.exception;

import sweetgirl.dataops.global.code.BaseErrorCode;
import sweetgirl.dataops.global.exception.CustomException;

public class ProposalException extends CustomException {

	public ProposalException(BaseErrorCode errorCode) {
		super(errorCode);
	}

	public ProposalException(BaseErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
