package sweetgirl.croissant.world.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 홈이 들어올 때마다 읽는 개인 상태. 카탈로그는 여기 섞지 않는다 — 8 행성 32 종까지 늘면 본문이
 * 커지는데, 회원과 무관한 내용이라 따로 캐시하는 편이 싸다.
 *
 * @param catalogVersion   들고 있는 카탈로그와 다르면 앱이 카탈로그를 다시 받는다
 * @param recordCount      지금 살아 있는 기록 수(지운 것 제외) — 화면에 보여주는 숫자
 * @param maxRecordCount   가장 높이 쌓았던 높이 — 진행도 판정은 이 값으로 한다
 * @param pendingRoulettePlanets 닿았는데 아직 룰렛을 안 돌린 행성
 * @param level            레벨(1 부터). 행성 순서 × 고도 단계 순서다
 * @param levelUpAt        다음 레벨이 되는 기록 수. 마지막 레벨이면 null
 * @param welcomedAt       첫 친구 연출을 본 시각. null 이면 아직 못 봤다 — 첫 조회가 마스코트를 쥐여
 *                         주므로 characters 로는 처음 온 회원인지 가릴 수 없다
 */
public record MemberWorldResponse(
		int catalogVersion,
		long recordCount,
		int maxRecordCount,
		String planet,
		String stage,
		String nextPlanet,
		int toNext,
		double ratio,
		String selectedCharacter,
		List<String> pendingRoulettePlanets,
		int wakeTickets,
		List<MemberCharacterResponse> characters,
		LocalDateTime welcomedAt,
		int level,
		Integer levelUpAt,
		/** 지난주 전국 주간 랭킹 1 위라 왕관을 쓰고 있는가 */
		boolean crowned) {
}
