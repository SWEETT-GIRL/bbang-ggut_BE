package sweetgirl.croissant.admin.dto.request;

public record BusinessHourRequest(
		int openHour,
		int openMin,
		int closeHour,
		int closeMin,
		boolean closed) {
}
