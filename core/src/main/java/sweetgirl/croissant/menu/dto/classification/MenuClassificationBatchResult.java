package sweetgirl.croissant.menu.dto.classification;

/**
 * 배치 한 번의 결과 — 로그·모니터링용.
 *
 * @param total        주워 온 메뉴 수
 * @param cacheHits    캐시로 해결한 고유 이름 수 (AI 호출 없음)
 * @param aiClassified AI 에 물은 고유 이름 수
 * @param agreed       그중 3회 일치로 kind 확정
 * @param nonBakery    그중 비-빵 판정 ('기타' 확정)
 * @param undecided    그중 갈려서 검수로 넘긴 것
 */
public record MenuClassificationBatchResult(
		int total,
		int cacheHits,
		int aiClassified,
		int agreed,
		int nonBakery,
		int undecided) {

	public static MenuClassificationBatchResult empty() {
		return new MenuClassificationBatchResult(0, 0, 0, 0, 0, 0);
	}
}
