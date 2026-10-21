package sweetgirl.croissant.store.service.search;

import java.util.List;

import sweetgirl.croissant.store.enums.MatchedField;

/**
 * 검색 엔진이 돌려주는 가게 한 건의 랭킹 결과. 응답에 필요한 실제 데이터는 {@link StoreSearchAssembler}가 RDB에서 채운다.
 *
 * @param distanceMeters 거리순 정렬일 때만 채워진다.
 * @param matchedMenuIds 메뉴 검색이 있던 시절의 자리 — 지금 엔진은 항상 빈 목록. 조립기가 대표 메뉴 정렬에 참조하므로 남긴다.
 */
public record StoreSearchHit(
		Long storeId,
		Double score,
		Double distanceMeters,
		List<Long> matchedMenuIds,
		List<MatchedField> matchedFields) {
}
