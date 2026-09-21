package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;

/**
 * CS · 광고 오류 보상용. 비워 보낸 칸은 건드리지 않는다.
 *
 * @param characterCode 깨어난 채로 지급할 캐릭터
 * @param maxRecordCount 진행도(최고 높이). 실제 기록 수보다 낮추면 효과가 없다 — 조회가 큰 쪽을 쓴다
 * @param wakeTickets 발급할 깨우기 티켓 수
 */
public record AdminWorldMemberGrantRequest(
		String characterCode,
		@Min(0) Integer maxRecordCount,
		@Min(1) Integer wakeTickets) {
}
