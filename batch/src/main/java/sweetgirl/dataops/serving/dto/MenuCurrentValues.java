package sweetgirl.dataops.serving.dto;

/**
 * 운영 메뉴 현재값 — userAdded 는 사용자가 앱에서 직접 등록한 메뉴(created_by_member_id 있음), 네이버 값으로 덮지 않는다.
 * categoryId·category 는 menu_category_id 와 그 이름(세분류 kind) — 미분류면 둘 다 null. 정책 재판정(재분류)의 입력이다.
 */
public record MenuCurrentValues(Long menuId, String name, Integer price, String description, boolean userAdded,
		Long categoryId, String category) {
}
