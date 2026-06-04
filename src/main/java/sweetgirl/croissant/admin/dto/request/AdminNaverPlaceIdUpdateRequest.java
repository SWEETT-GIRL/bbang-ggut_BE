package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

// replaceMenus=true 면 store 의 기존 메뉴를 soft-delete 후 ID 갱신.
// null/false 면 ID 만 교체 (기본).
public record AdminNaverPlaceIdUpdateRequest(
		@NotBlank String naverPlaceId,
		Boolean replaceMenus,
		String address,
		CoordRequest coord) {

	public record CoordRequest(
			double latitude,
			double longitude) {
	}
}
