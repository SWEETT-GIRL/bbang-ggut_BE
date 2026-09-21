package sweetgirl.croissant.world.dto.response;

/**
 * 친구를 깨우는 중간 경과.
 *
 * @param characterCode 대상 친구
 * @param adViews       지금까지 모은 광고 편수
 * @param adsRequired   이 친구를 깨우는 데 필요한 편수. 행성마다 다를 수 있다
 * @param awakened      이번 호출로 깨어났는가
 */
public record WorldWakeResponse(
		String characterCode,
		int adViews,
		int adsRequired,
		boolean awakened) {
}
