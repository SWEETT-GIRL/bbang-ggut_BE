package sweetgirl.dataops.snapshot.parser;

import java.util.List;

/**
 * 스냅샷 payload 를 파싱한 정규화 결과 — 판정층(reconcile)의 입력.
 * businessHoursRaw 는 참조 해소를 마친 원본 JSON 텍스트 (구조화는 BusinessHoursParser).
 * address(지번)·roadAddress(도로명)·좌표는 이전(移轉)·오매칭 감지 입력 (150m 임계값 — field-tracks-plan ②).
 * baeminMenus 는 배민 연동 가게에만 존재 — 신규 메뉴 추가의 보조 소스 (음료 제외 필터는 판정층 적용).
 */
public record ParsedPlace(
		String name,
		String phone,
		String address,
		String roadAddress,
		Double longitude,
		Double latitude,
		String businessStatusDescription,
		String businessHoursRaw,
		String instagramHandle,
		List<ParsedMenu> menus,
		List<ParsedMenu> baeminMenus) {

	/**
	 * 네이버에 가게 페이지가 없다 — placeDetail 이 null 인 응답(삭제·통합된 플레이스, 09-04~06 스윕 1,045곳).
	 * 살아 있는 페이지는 PlaceDetailBase.name 이 항상 있으므로 이름 부재로 판별한다.
	 */
	public boolean pageMissing() {
		return name == null;
	}
}
