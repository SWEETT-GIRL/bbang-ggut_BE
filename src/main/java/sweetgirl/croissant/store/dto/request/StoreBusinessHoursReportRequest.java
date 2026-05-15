package sweetgirl.croissant.store.dto.request;

import java.time.DayOfWeek;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StoreBusinessHoursReportRequest(
		@NotNull @Size(min = 1) List<BusinessHourEntry> hours,
		String reason) {
	public record BusinessHourEntry(
			@NotNull DayOfWeek dayOfWeek,
			@NotNull Boolean closed,
			String openTime, // HH:mm, closed=false일 때 필수
			String closeTime // HH:mm, closed=false일 때 필수
	) {
	}
}
