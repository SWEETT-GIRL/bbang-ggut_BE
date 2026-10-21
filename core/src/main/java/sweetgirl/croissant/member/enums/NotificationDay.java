package sweetgirl.croissant.member.enums;

import sweetgirl.croissant.member.code.DayErrorCode;
import sweetgirl.croissant.member.exception.DayException;

public enum NotificationDay {
	MONDAY("월"),
	TUESDAY("화"),
	WEDNESDAY("수"),
	THURSDAY("목"),
	FRIDAY("금"),
	SATURDAY("토"),
	SUNDAY("일");

	private final String day;

	NotificationDay(String day) {
		this.day = day;
	}

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
