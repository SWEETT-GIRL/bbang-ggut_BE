package sweetgirl.croissant.external.google.dto;

import java.util.List;

public record GoogleFindPlaceResponse(
		List<Place> places) {
	public record Place(
			String id) {
	}
}
