package sweetgirl.croissant.world.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 캐릭터를 어떻게 얻었는가. 해금 경로별 효과를 나중에 SQL 로 되짚기 위해 남긴다.
 */
@Getter
@RequiredArgsConstructor
public enum AcquiredBy {

	FIRST("첫 설치 마스코트"),
	ROULETTE("행성 도착 룰렛"),
	AD("광고 시청"),
	REFERRAL("친구 추천 지름길"),
	ADMIN("어드민 지급");

	private final String description;
}
