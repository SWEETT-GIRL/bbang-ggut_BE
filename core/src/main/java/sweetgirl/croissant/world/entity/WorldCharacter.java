package sweetgirl.croissant.world.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/**
 * 캐릭터(친구). {@code code} 는 앱의 CharacterId 와 1:1 이다.
 *
 * <p>이름의 정본은 앱이 아니라 이 표다 — 어드민에서 고치면 앱 배포 없이 바뀐다.
 * 마스코트는 앱 전체에 하나뿐이고(부분 유니크 인덱스), 첫 설치 때 바로 지급된다.
 */
@Entity
@Table(name = "world_characters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldCharacter extends BaseTimeEntity {

	@Id
	@Column(length = 30)
	private String code;

	@Column(nullable = false, length = 30)
	private String name;

	/** 프로필에 이름과 함께 나오는 한 줄 소개. 없으면 앱이 번들 문구를 쓴다. */
	@Column(length = 100)
	private String description;

	@Column(name = "planet_code", nullable = false, length = 30)
	private String planetCode;

	/** 행성 안에서의 자리. */
	@Column(nullable = false)
	private int sortOrder;

	/** 첫 설치 때 바로 주는 한 명인가. */
	@Column(nullable = false)
	private boolean mascot;

	@Column(nullable = false)
	private boolean active;

	public static WorldCharacter create(
			String code,
			String name,
			String description,
			String planetCode,
			int sortOrder,
			boolean mascot) {
		WorldCharacter character = new WorldCharacter();
		character.code = code;
		character.name = name;
		character.description = description;
		character.planetCode = planetCode;
		character.sortOrder = sortOrder;
		character.mascot = mascot;
		character.active = true;
		return character;
	}

	/** 비워 보낸 칸은 지금 값을 그대로 둔다. 소개를 지우는 건 {@link #changeDescription} 으로 한다. */
	public void update(
			String name,
			String planetCode,
			Integer sortOrder,
			Boolean mascot,
			Boolean active) {
		if (name != null) {
			this.name = name;
		}
		if (planetCode != null) {
			this.planetCode = planetCode;
		}
		if (sortOrder != null) {
			this.sortOrder = sortOrder;
		}
		if (mascot != null) {
			this.mascot = mascot;
		}
		if (active != null) {
			this.active = active;
		}
	}

	/** 소개를 바꾼다. null 이면 소개를 지워, 앱이 번들 문구로 돌아간다. */
	public void changeDescription(String description) {
		this.description = description;
	}
}
