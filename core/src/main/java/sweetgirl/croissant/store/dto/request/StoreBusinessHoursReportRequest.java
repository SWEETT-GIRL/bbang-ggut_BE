package sweetgirl.croissant.store.dto.request;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;

public record StoreBusinessHoursReportRequest(
		@NotNull @Size(min = 1) List<BusinessHourEntry> hours,
		String reason) {

	public List<StoreBusinessHour> toBusinessHours(Store store) {
		return hours.stream()
			.map(hour -> hour.toEntity(store))
			.toList();
	}

	public record BusinessHourEntry(
			@NotNull DayOfWeek dayOfWeek,
			@NotNull Boolean closed,
			String openTime, // HH:mm, closed=false일 때 필수
			String closeTime // HH:mm, closed=false일 때 필수
	) {
		private StoreBusinessHour toEntity(Store store) {
			return new StoreBusinessHour(
				store,
				dayOfWeek,
				closed ? null : LocalTime.parse(openTime),
				closed ? null : LocalTime.parse(closeTime),
				closed);
		}
	}
}
