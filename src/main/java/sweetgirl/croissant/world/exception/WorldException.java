package sweetgirl.croissant.world.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.world.code.WorldErrorCode;

public class WorldException extends CustomException {

	public WorldException(WorldErrorCode errorCode) {
		super(errorCode);
	}
}
