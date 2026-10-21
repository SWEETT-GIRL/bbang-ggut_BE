package sweetgirl.croissant.dessert.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.dessert.enums.DessertItemType;
import sweetgirl.croissant.dessert.enums.DessertTrendStatus;

/**
 * 요즘 뜨는 디저트 한 종류.
 *
 * <p>디저트 파이프라인이 쓰고 BE 는 읽기만 한다. 그래서 상태를 바꾸는 도메인 메서드가 없다.
 *
 * <p>표의 {@code aliases}(검색 별칭 배열)는 매핑하지 않는다. 수집 스크립트가 블로그·인스타를
 * 검색할 때 쓰는 값이라 API 가 읽을 일이 없고, Postgres 배열을 굳이 Hibernate 로 끌어올 이유가 없다.
 */
@Entity
@Table(name = "dessert_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DessertItem extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50, unique = true)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "item_type", nullable = false, length = 20)
	private DessertItemType itemType;

	/** CONVENIENCE·CHAIN 의 판매 브랜드 (GS25, 창억떡 …). GENERIC 은 null */
	@Column(length = 50)
	private String brand;

	@Enumerated(EnumType.STRING)
	@Column(name = "trend_status", length = 20)
	private DessertTrendStatus trendStatus;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	@Column(name = "launched_at")
	private LocalDate launchedAt;

	@Column(name = "ended_at")
	private LocalDate endedAt;
}
