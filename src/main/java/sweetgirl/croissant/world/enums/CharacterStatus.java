package sweetgirl.croissant.world.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 회원이 가진 캐릭터의 상태. 행은 지연 생성이라 아직 아무것도 안 한 캐릭터는 행 자체가 없다.
 */
@Getter
@RequiredArgsConstructor
public enum CharacterStatus {

	SLEEPING("자고 있다 — 광고를 보는 중이지만 아직 다 못 깨웠다"),
	AWAKE("깨어났다 — 데리고 다닐 수 있다");

	private final String description;
}
