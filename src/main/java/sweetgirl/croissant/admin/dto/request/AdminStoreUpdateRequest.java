package sweetgirl.croissant.admin.dto.request;

import java.util.Map;

public record AdminStoreUpdateRequest(
		String name,
		String address,
		CoordRequest coord,
		String phone,
		String status,
		String archivedAt,
		Map<String, BusinessHourRequest> hours) {

	public record CoordRequest(double latitude, double longitude) {
	}
}
