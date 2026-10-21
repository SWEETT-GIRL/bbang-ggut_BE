package sweetgirl.croissant.store.entity;

import java.math.BigDecimal;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.enums.StoreSearchRegionMappingType;

@Entity
@Table(name = "store_search_region", uniqueConstraints = @UniqueConstraint(columnNames = {
	"store_id", "region_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreSearchRegion extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "region_id", nullable = false)
	private SearchRegion region;

	@Column(nullable = false, precision = 4, scale = 2)
	private BigDecimal weight;

	@Enumerated(EnumType.STRING)
	@Column(name = "mapping_type", nullable = false, length = 30)
	private StoreSearchRegionMappingType mappingType;

	@Column(name = "is_primary", nullable = false)
	private Boolean isPrimary;

	@Builder
	public StoreSearchRegion(Store store, SearchRegion region, BigDecimal weight,
			StoreSearchRegionMappingType mappingType, Boolean isPrimary) {
		this.store = store;
		this.region = region;
		this.weight = weight != null ? weight : BigDecimal.ONE;
		this.mappingType = mappingType != null ? mappingType : StoreSearchRegionMappingType.MANUAL;
		this.isPrimary = isPrimary != null ? isPrimary : false;
	}
}
