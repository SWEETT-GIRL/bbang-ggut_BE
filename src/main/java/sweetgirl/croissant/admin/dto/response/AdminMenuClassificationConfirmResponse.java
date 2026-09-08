package sweetgirl.croissant.admin.dto.response;

/**
 * @param appliedMenus 이 이름의 미분류 활성 메뉴 중 이번에 kind 를 받은 수. 이미 분류된 메뉴는 건드리지 않는다
 */
public record AdminMenuClassificationConfirmResponse(
		String normalizedName,
		String kind,
		boolean bakeryItem,
		int appliedMenus) {
}
