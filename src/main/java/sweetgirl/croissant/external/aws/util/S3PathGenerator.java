package sweetgirl.croissant.external.aws.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.external.aws.enums.S3Folder;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class S3PathGenerator {

	public static String getStorePath(Long storeId) {
		return String.format("%s/%d", S3Folder.STORES.getValue(), storeId);
	}

	public static String getMenuPath(Long menuId) {
		return String.format("%s/%d", S3Folder.MENUS.getValue(), menuId);
	}

	public static String getReportStoreImagePath(Long storeId) {
		return String.format("%s/stores/%d/images",
			S3Folder.REPORTS.getValue(), storeId);
	}

	public static String getReportMenuPath(Long storeId, Long menuId) {
		String base = String.format("%s/stores/%d/menus", S3Folder.REPORTS.getValue(), storeId);

		String subPath = (menuId != null) ? String.valueOf(menuId) : "new";

		return base + "/" + subPath;
	}
}
