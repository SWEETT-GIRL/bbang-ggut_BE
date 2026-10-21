package sweetgirl.croissant.breadranking.dto.response;

import java.util.List;

/**
 * 순위를 바꾼 뒤 — 1등이 바뀌었는지와 함께 바뀐 순위 전체.
 * 앱 토스트("소금빵 최애로 한 표 들어갔어요")는 {@code topChanged} 로 판단한다.
 *
 * @param previousTop 바꾸기 전 1등(공동이면 여러 개)
 * @param currentTop  바꾼 뒤 1등
 */
public record MyBreadRankingChangeResponse(
		List<StoreSummaryResponse> previousTop,
		List<StoreSummaryResponse> currentTop,
		boolean topChanged,
		MyBreadRankingResponse ranking) {
}
