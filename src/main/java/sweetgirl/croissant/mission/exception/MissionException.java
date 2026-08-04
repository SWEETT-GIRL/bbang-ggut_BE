package sweetgirl.croissant.mission.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.mission.code.MissionErrorCode;

public class MissionException extends CustomException {

	public MissionException(MissionErrorCode errorCode) {
		super(errorCode);
	}
}
