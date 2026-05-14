package sweetgirl.croissant.alarm.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum DeviceOs {
	IOS, ANDROID;

	@JsonCreator
	public static DeviceOs from(String value) {
		if (value == null) {
			return null;
		}
		return DeviceOs.valueOf(value.trim().toUpperCase(Locale.ROOT));
	}
}
