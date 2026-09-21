package sweetgirl.croissant.world.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.world.enums.PlanetGroup;

/**
 * 여정의 한 목적지. 태양계 8 행성도 외계 행성도 나만의 행성도 같은 표에 산다.
 *
 * <p>{@code span} 은 절대 임계값이 아니라 <b>이 행성을 졸업하는 데 필요한 기록 수</b>다.
 * 외계 행성은 여럿 중 골라 가므로 도착점을 절대값으로 굳히면 고르는 순서가 난이도를 바꾼다.
 * 절대 임계값은 {@code unlockAfter} 사슬을 따라 누적해 서버가 만든다.
 *
 * <p>{@code unlockAfter} 는 DB 에서 자기 참조 FK 지만 연관 관계로 매핑하지 않았다.
 * 카탈로그는 늘 통째로 읽어 메모리에서 사슬을 잇기 때문에, 행성마다 지연 로딩을 걸 이유가 없다.
 */
@Entity
@Table(name = "world_planets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldPlanet extends BaseTimeEntity {

	@Id
	@Column(length = 30)
	private String code;

	@Column(nullable = false, length = 30)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "planet_group", nullable = false, length = 20)
	private PlanetGroup planetGroup;

	/** 같은 갈래 안에서의 순서. */
	@Column(nullable = false)
	private int sortOrder;

	/** 이 행성 구간의 길이 — 졸업까지 필요한 기록 수. */
	@Column(nullable = false)
	private int span;

	/** 이 행성을 지나야 열린다. 갈래의 첫 행성이면 비어 있다. */
	@Column(name = "unlock_after", length = 30)
	private String unlockAfter;

	/** 같은 갈래에서 골라 가는가(외계). */
	@Column(nullable = false)
	private boolean selectable;

	/** 도착 시 룰렛으로 주는 인원. 지구는 0 이다 — 첫 친구는 마스코트로 바로 준다. */
	@Column(nullable = false)
	private int rouletteSize;

	/** 잠든 친구 하나를 깨우는 데 드는 광고 편수. 읽는 쪽은 상수를 쓰지 않고 이 값을 본다. */
	@Column(nullable = false)
	private int adsPerWake;

	/** 추천 지름길(티켓)로 깨우기를 허용하는가. */
	@Column(nullable = false)
	private boolean referralOk;

	/** 여정맵 아이콘. 비어 있으면 앱이 번들 그림을 쓴다. */
	@Column(length = 255)
	private String imageKey;

	@Column(nullable = false)
	private boolean active;

	private WorldPlanet(String code, PlanetGroup planetGroup) {
		this.code = code;
		this.planetGroup = planetGroup;
		this.active = true;
	}

	public static WorldPlanet create(
			String code,
			String name,
			PlanetGroup planetGroup,
			int sortOrder,
			int span,
			String unlockAfter,
			boolean selectable,
			int rouletteSize,
			int adsPerWake,
			boolean referralOk,
			String imageKey) {
		WorldPlanet planet = new WorldPlanet(code, planetGroup);
		planet.name = name;
		planet.sortOrder = sortOrder;
		planet.span = span;
		planet.unlockAfter = unlockAfter;
		planet.selectable = selectable;
		planet.rouletteSize = rouletteSize;
		planet.adsPerWake = adsPerWake;
		planet.referralOk = referralOk;
		planet.imageKey = imageKey;
		return planet;
	}

	/** 비워 보낸 칸은 지금 값을 그대로 둔다. 어드민 화면이 일부만 고쳐 보낼 수 있게. */
	public void update(
			String name,
			Integer sortOrder,
			Integer span,
			String unlockAfter,
			Boolean selectable,
			Integer rouletteSize,
			Integer adsPerWake,
			Boolean referralOk,
			String imageKey,
			Boolean active) {
		if (name != null) {
			this.name = name;
		}
		if (sortOrder != null) {
			this.sortOrder = sortOrder;
		}
		if (span != null) {
			this.span = span;
		}
		if (unlockAfter != null) {
			this.unlockAfter = unlockAfter;
		}
		if (selectable != null) {
			this.selectable = selectable;
		}
		if (rouletteSize != null) {
			this.rouletteSize = rouletteSize;
		}
		if (adsPerWake != null) {
			this.adsPerWake = adsPerWake;
		}
		if (referralOk != null) {
			this.referralOk = referralOk;
		}
		if (imageKey != null) {
			this.imageKey = imageKey;
		}
		if (active != null) {
			this.active = active;
		}
	}
}
