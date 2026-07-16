package sweetgirl.croissant.menu.dto.projection;

import sweetgirl.croissant.menu.enums.MenuKind;

/** 가게별 메뉴 이미지 조회용 — 트렌드 썸네일 폴백 체인(카테고리 메뉴 → 아무 메뉴)에 사용 */
public interface MenuImageByStoreProjection {

	Long getStoreId();

	MenuKind getMenuKind();

	String getResizedKey320();

	String getAwsKey();
}
