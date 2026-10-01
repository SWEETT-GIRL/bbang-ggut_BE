package sweetgirl.croissant.breadranking.dto.response;

import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse.Chip;

/**
 * 빵집 랭킹 한 줄.
 *
 * @param voters     이 가게를 1등으로 매긴 사람 수 — 앱 표기 "N명 최애"
 * @param againRate  또 먹을래요 비율(0~1). 평가가 없으면 null
 * @param topTexture 대표 식감. 없으면 null
 * @param rankChange 어제 대비(양수 ▲, 음수 ▼, 0 같음 — 0 이면 앱은 아무것도 안 그린다). 어제 스냅샷이 없거나 NEW 면 null
 * @param isNew      어제 상위 100 에 없던 가게. 어제 스냅샷이 없으면 null
 */
public record BreadBoardEntryResponse(
		int rank,
		StoreSummaryResponse store,
		long voters,
		Double againRate,
		Chip topTexture,
		Integer rankChange,
		Boolean isNew) {
}
