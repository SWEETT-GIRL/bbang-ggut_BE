package sweetgirl.croissant.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.enums.SearchRegionLevel;
import sweetgirl.croissant.store.enums.SearchRegionType;

@Entity
@Table(name = "search_region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchRegion extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SearchRegionType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private SearchRegionLevel level;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_region_id")
	private SearchRegion parentRegion;

	@Column(name = "center_lat")
	private Double centerLat;

	@Column(name = "center_lng")
	private Double centerLng;

	@Column(name = "radius_meters")
	private Integer radiusMeters;

	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;

	@Column(name = "is_active", nullable = false)
	private Boolean isActive;

	@Builder
	public SearchRegion(String name, SearchRegionType type, SearchRegionLevel level, SearchRegion parentRegion,
			Double centerLat, Double centerLng, Integer radiusMeters, Integer displayOrder, Boolean isActive) {
		this.name = name;
		this.type = type;
		this.level = level;
		this.parentRegion = parentRegion;
		this.centerLat = centerLat;
		this.centerLng = centerLng;
		this.radiusMeters = radiusMeters;
		this.displayOrder = displayOrder != null ? displayOrder : 0;
		this.isActive = isActive != null ? isActive : true;
	}
}
