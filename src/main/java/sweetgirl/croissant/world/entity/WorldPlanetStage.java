package sweetgirl.croissant.world.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행성 안의 고도 단계(마을 · 하늘 · 우주).
 *
 * <p>단계 수는 행성마다 다를 수 있다. 읽는 쪽은 3 단계를 가정하지 말고 {@code stageOrder} 와
 * {@code offsetAt} 만으로 판정해야 한다.
 *
 * <p>{@code offsetAt} 은 행성 도착점 기준 <b>상대</b> 위치다(0 = 도착 즉시). 절대값으로 두면
 * 행성 순서가 바뀔 때 전부 다시 계산해야 한다.
 */
@Entity
@Table(name = "world_planet_stages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldPlanetStage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "planet_code", nullable = false, length = 30)
	private String planetCode;

	@Column(nullable = false, length = 20)
	private String code;

	@Column(nullable = false, length = 30)
	private String name;

	@Column(nullable = false)
	private int stageOrder;

	/** 행성 도착점 기준 상대 위치. */
	@Column(nullable = false)
	private int offsetAt;

	/** 배경 그림. 비어 있으면 앱이 번들 그림을 쓴다. */
	@Column(length = 255)
	private String imageKey;
}
