package sweetgirl.croissant.breadranking.dto.response;

import java.util.List;

/**
 * 한 빵 종류의 빵집 랭킹.
 *
 * @param totalVotes   이 종류의 전체 표 수
 * @param minVotes     순위가 열리는 최소 표 수
 * @param missingVotes 모자란 표 수 — 0 이면 열린 것. 앱 F4 "N표만 더 모이면 열려요"
 * @param entries      열렸을 때만 채운다
 */
public record BreadBoardResponse(
		BreadCategoryResponse category,
		long totalVotes,
		int minVotes,
		int missingVotes,
		List<BreadBoardEntryResponse> entries) {
}
