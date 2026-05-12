package sweetgirl.croissant.common.enums;

import sweetgirl.croissant.member.code.DayErrorCode;
import sweetgirl.croissant.member.exception.DayException;

public enum NotificationDay {
	MONDAY,
	TUESDAY,
	WEDNESDAY,
	THURSDAY,
	FRIDAY,
	SATURDAY,
	SUNDAY;

	public static NotificationDay from(String input) {
		if (input == null || input.isBlank()) {
			throw new DayException(DayErrorCode.DAY_NOT_FOUND);
		}
		for (NotificationDay notification : values()) {
			if (notification.name().equals(input.trim().toUpperCase())) {
				return notification;
			}
		}
		throw new DayException(DayErrorCode.DAY_NOT_FOUND);
	}
}
