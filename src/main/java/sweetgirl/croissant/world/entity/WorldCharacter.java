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
}
