package sweetgirl.croissant.admin.dto.request;

public record AdminMenuUpdateRequest(
		String name,
		String price,
		String desc,
		String category,
		String status) {
}
