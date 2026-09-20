package sweetgirl.croissant.world.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카탈로그가 바뀐 횟수. 표에 행은 언제나 하나뿐이다(CHECK id = 1).
 *
 * <p>앱은 이 값만 비교해 카탈로그를 다시 받을지 정하고, 서버도 이 값으로 캐시를 푼다.
 * 어드민이 행성 · 단계 · 캐릭터 · 에셋 중 무엇이든 저장하면 같은 트랜잭션에서 올려야 한다 —
 * 빠뜨리면 "어드민으로 바꿀 수 있다"가 거짓말이 된다.
 */
@Entity
@Table(name = "world_catalog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldCatalogVersion {

	/** 언제나 1. */
	public static final short SINGLE_ROW_ID = 1;

	@Id
	private Short id;

	@Column(nullable = false)
	private int version;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
