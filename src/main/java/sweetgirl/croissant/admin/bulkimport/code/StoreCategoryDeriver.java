package sweetgirl.croissant.admin.bulkimport.code;

import sweetgirl.croissant.store.enums.StoreCategory;

public final class StoreCategoryDeriver {

	private StoreCategoryDeriver() {

	}

	public static StoreCategory derive(String kakaoCategoryName) {
		if (kakaoCategoryName == null) {
			return null;
		}
		if (kakaoCategoryName.contains("양식")) {
			return StoreCategory.WESTERN;
		}
		return null;
	}
}
