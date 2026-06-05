package sweetgirl.croissant.external.aws.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum S3Folder {
	STORES("stores"),
	MENUS("menus"),
	REVIEWS("reviews"),
	REPORTS("reports"),
	MENU_ADD_REPORTS("menu-add-reports"),
	BANNERS("banners");

	private final String value;

	public String prefix() {
		return value + "/";
	}
}
