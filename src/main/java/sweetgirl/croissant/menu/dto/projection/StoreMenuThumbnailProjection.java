package sweetgirl.croissant.menu.dto.projection;

import sweetgirl.croissant.menu.enums.MenuKind;

// 트렌드 랭킹에서 가게별 대표 메뉴 사진 선택용. (가게 - 메뉴 - 메뉴이미지) 조인 결과 한 행 = 이미지 하나.
public interface StoreMenuThumbnailProjection {
	Long getStoreId();

	Long getMenuId();

	MenuKind getMenuKind();

	Integer getMenuDisplayOrder();

	String getAwsKey();

	String getResizedKey320();
}
