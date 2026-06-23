package sweetgirl.croissant.external.aws.util;

import java.util.UUID;

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

	public static String getReviewImagePath(Long memberId) {
		return String.format("%s/members/%d/images", S3Folder.REVIEWS.getValue(), memberId);
	}

	public static String getReportStoreImagePath(Long memberId, Long storeId) {
		return String.format("%s/stores/%d/members/%d/images",
			S3Folder.REPORTS.getValue(), storeId, memberId);
	}

	public static String getReportMenuPath(Long memberId, Long storeId, Long menuId) {
		String base = String.format("%s/stores/%d/menus", S3Folder.REPORTS.getValue(), storeId);
		String subPath = (menuId != null) ? String.valueOf(menuId) : "new";
		return String.format("%s/%s/members/%d", base, subPath, memberId);
	}

	public static String getMenuAddReportTempPath() {
		return String.format("%s/temp/%s", S3Folder.MENU_ADD_REPORTS.getValue(), UUID.randomUUID());
	}

	public static String getBannerPath() {
		return String.format("%s/%s", S3Folder.BANNERS.getValue(), UUID.randomUUID());
	}

	public static String getCoursePath(Long courseId) {
		return String.format("%s/%d", S3Folder.COURSES.getValue(), courseId);
	}
}
