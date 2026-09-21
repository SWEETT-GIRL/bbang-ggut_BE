package sweetgirl.croissant.world.entity;

import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.enums.CharacterStatus;
import sweetgirl.croissant.world.enums.PlanetGroup;
import sweetgirl.croissant.world.enums.WakeTicketSource;

/**
 * 카탈로그 엔티티는 JPA 가 읽기만 하는 콘텐츠라 운영 코드에 생성 경로가 없다. 그래서 테스트 픽스처를
 * 엔티티와 <b>같은 패키지</b>에 두고 protected 기본 생성자로 만든다 — 테스트 때문에 운영 엔티티에
 * 쓰지도 않을 정적 팩토리를 열지 않기 위해서다.
 */
public final class WorldFixtures {

	private WorldFixtures() {
		// 픽스처 모음 — 인스턴스를 만들지 않는다
	}

	public static WorldPlanet planet(
			String code,
			String name,
			int sortOrder,
			int span,
			String unlockAfter,
			int rouletteSize,
			String imageKey) {
		WorldPlanet planet = new WorldPlanet();
		set(planet, "code", code);
		set(planet, "name", name);
		set(planet, "planetGroup", PlanetGroup.SOLAR);
		set(planet, "sortOrder", sortOrder);
		set(planet, "span", span);
		set(planet, "unlockAfter", unlockAfter);
		set(planet, "selectable", false);
		set(planet, "rouletteSize", rouletteSize);
		set(planet, "adsPerWake", 2);
		set(planet, "referralOk", true);
		set(planet, "imageKey", imageKey);
		set(planet, "active", true);
		return planet;
	}

	public static WorldPlanetStage stage(
			String planetCode,
			String code,
			String name,
			int stageOrder,
			int offsetAt) {
		WorldPlanetStage stage = new WorldPlanetStage();
		set(stage, "planetCode", planetCode);
		set(stage, "code", code);
		set(stage, "name", name);
		set(stage, "stageOrder", stageOrder);
		set(stage, "offsetAt", offsetAt);
		return stage;
	}

	public static WorldCharacter character(
			String code,
			String name,
			String planetCode,
			int sortOrder,
			boolean mascot) {
		WorldCharacter character = new WorldCharacter();
		set(character, "code", code);
		set(character, "name", name);
		set(character, "planetCode", planetCode);
		set(character, "sortOrder", sortOrder);
		set(character, "mascot", mascot);
		set(character, "active", true);
		return character;
	}

	public static WorldCharacterAsset asset(String characterCode, String pose, String imageKey) {
		WorldCharacterAsset asset = new WorldCharacterAsset();
		set(asset, "id", new WorldCharacterAssetId(characterCode, pose));
		set(asset, "imageKey", imageKey);
		return asset;
	}

	public static MemberWorld memberWorld(Long memberId, String selectedCharacter, int maxRecordCount) {
		MemberWorld memberWorld = MemberWorld.start(memberId, selectedCharacter);
		set(memberWorld, "maxRecordCount", maxRecordCount);
		return memberWorld;
	}

	public static MemberPlanetArrival arrival(Long memberId, String planetCode) {
		return arrival(memberId, planetCode, false);
	}

	public static MemberPlanetArrival arrival(Long memberId, String planetCode, boolean spunRoulette) {
		MemberPlanetArrival arrival = new MemberPlanetArrival();
		set(arrival, "id", new MemberPlanetArrivalId(memberId, planetCode));
		set(arrival, "arrivedAt", java.time.LocalDateTime.now());
		set(arrival, "rouletteAt", spunRoulette ? java.time.LocalDateTime.now() : null);
		return arrival;
	}

	public static MemberCharacter memberCharacter(
			Long memberId,
			String characterCode,
			CharacterStatus status,
			int adViews,
			AcquiredBy acquiredBy) {
		MemberCharacter memberCharacter = new MemberCharacter();
		set(memberCharacter, "id", new MemberCharacterId(memberId, characterCode));
		set(memberCharacter, "status", status);
		set(memberCharacter, "adViews", adViews);
		set(memberCharacter, "acquiredBy", acquiredBy);
		set(memberCharacter, "acquiredAt",
			acquiredBy == null ? null : java.time.LocalDateTime.now());
		return memberCharacter;
	}

	public static MemberWakeTicket wakeTicket(Long id, Long memberId, WakeTicketSource source) {
		MemberWakeTicket ticket = new MemberWakeTicket();
		set(ticket, "id", id);
		set(ticket, "memberId", memberId);
		set(ticket, "source", source);
		set(ticket, "createdAt", java.time.LocalDateTime.now());
		return ticket;
	}

	private static void set(Object target, String name, Object value) {
		ReflectionTestUtils.setField(target, name, value);
	}
}
