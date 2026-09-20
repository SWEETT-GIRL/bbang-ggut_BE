package sweetgirl.croissant.world.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 깨우기 티켓이 어디서 났는가. 티켓 한 장이면 광고를 건너뛰고 친구 하나를 바로 깨운다.
 */
@Getter
@RequiredArgsConstructor
public enum WakeTicketSource {

	REFERRAL("내 코드로 누가 가입했다"),
	ADMIN("어드민 지급 — CS · 광고 오류 보상"),
	EVENT("이벤트");

	private final String description;
}
