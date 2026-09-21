package sweetgirl.croissant.world.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 행성이 속한 갈래. 여정은 직선이 아니라서 갈래마다 고르는 방식이 다르다.
 */
@Getter
@RequiredArgsConstructor
public enum PlanetGroup {

	SOLAR("태양계 — 정해진 순서대로 지나간다"),
	ALIEN("외계 — 여럿 중 골라서 간다"),
	CUSTOM("나만의 행성");

	private final String description;
}
