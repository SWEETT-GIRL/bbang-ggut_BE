package sweetgirl.croissant.reward.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import sweetgirl.croissant.reward.enums.HomeCardType;

/**
 * 홈의 할 일 카드 한 장. 문구는 앱이 만든다 — 서버는 종류와 재료만 준다.
 * 종류마다 쓰는 필드가 다르고, 안 쓰는 필드는 응답에서 빠진다.
 *
 * @param planet   ROULETTE — 룰렛을 돌릴 행성
 * @param courseId TOUR_TODAY · TOUR_SOON — 그 빵투어
 * @param visited  TOUR_TODAY — 다녀온 가게 수
 * @param total    TOUR_TODAY — 빵투어의 가게 수
 * @param letterId LETTER — 열 편지
 * @param daysLeft TOUR_SOON — 빵투어까지 남은 날
 * @param nextStoreName TOUR_TODAY — 다음에 갈 가게(방문 순서에서 아직 안 간 첫 가게). 다 다녀왔으면 null
 * @param coverImageKey TOUR_TODAY · TOUR_SOON — 빵투어 표지 사진 키. 없으면 null
 * @param storeCount    TOUR_SOON — 빵투어의 가게 수
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HomeCardResponse(
		HomeCardType type,
		String planet,
		Long courseId,
		String title,
		Integer visited,
		Integer total,
		Long letterId,
		Integer daysLeft,
		String nextStoreName,
		String coverImageKey,
		Integer storeCount) {

	public static HomeCardResponse roulette(String planet) {
		return new HomeCardResponse(HomeCardType.ROULETTE, planet, null, null, null, null, null, null, null, null,
			null);
	}

	public static HomeCardResponse tourToday(
			Long courseId, String title, int visited, int total, String nextStoreName, String coverImageKey) {
		return new HomeCardResponse(HomeCardType.TOUR_TODAY, null, courseId, title, visited, total, null, null,
			nextStoreName, coverImageKey, null);
	}

	public static HomeCardResponse letter(Long letterId) {
		return new HomeCardResponse(HomeCardType.LETTER, null, null, null, null, null, letterId, null, null, null,
			null);
	}

	public static HomeCardResponse tourSoon(
			Long courseId, String title, int daysLeft, int storeCount, String coverImageKey) {
		return new HomeCardResponse(HomeCardType.TOUR_SOON, null, courseId, title, null, null, null, daysLeft, null,
			coverImageKey, storeCount);
	}
}
