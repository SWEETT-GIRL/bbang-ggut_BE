package sweetgirl.croissant.world.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CS 가 한 회원의 세계관 상태를 들여다보는 화면. "광고를 봤는데 안 깨어났어요" 같은 문의에
 * 광고 적립 수와 도착 · 룰렛 이력으로 답한다.
 */
public record AdminWorldMemberResponse(
		Long memberId,
		String selectedCharacter,
		int maxRecordCount,
		long recordCount,
		LocalDateTime welcomedAt,
		int unusedWakeTickets,
		List<MemberCharacterResponse> characters,
		List<Arrival> arrivals) {

	public record Arrival(String planetCode, LocalDateTime arrivedAt, LocalDateTime rouletteAt) {
	}
}
