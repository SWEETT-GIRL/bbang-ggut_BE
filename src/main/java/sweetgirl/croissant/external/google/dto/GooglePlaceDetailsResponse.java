package sweetgirl.croissant.external.google.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GooglePlaceDetailsResponse(
		@JsonProperty("regularOpeningHours") OpeningHours regularOpeningHours) {
	public record OpeningHours(
			List<Period> periods) {
	}

	public record Period(
			TimePoint open,
			TimePoint close) {
	}

	/**
	 * day: 0=일, 1=월, 2=화, 3=수, 4=목, 5=금, 6=토 (Google 기준)
	 */
	public record TimePoint(
			int day,
			int hour,
			int minute) {
	}
}
