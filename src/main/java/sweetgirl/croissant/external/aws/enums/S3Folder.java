package sweetgirl.croissant.external.aws.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum S3Folder {
	STORES("stores"),
	MENUS("menus"),
	REPORTS("reports");

	private final String value;
}
