package sweetgirl.dataops.snapshot.dto.response;

import sweetgirl.dataops.snapshot.parser.ParsedMenu;

public record ParsedMenuResponse(
		String name,
		String price,
		String description,
		String groupName,
		int imageCount) {

	public static ParsedMenuResponse from(ParsedMenu menu) {
		return new ParsedMenuResponse(
			menu.name(), menu.price(), menu.description(), menu.groupName(), menu.imageUrls().size());
	}
}
