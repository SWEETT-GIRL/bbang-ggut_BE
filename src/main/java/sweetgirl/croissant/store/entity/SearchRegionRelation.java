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
import sweetgirl.croissant.store.enums.SearchRegionRelationType;

@Entity
@Table(name = "search_region_relation", uniqueConstraints = @UniqueConstraint(columnNames = {
	"from_region_id", "to_region_id", "relation_type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchRegionRelation extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "from_region_id", nullable = false)
	private SearchRegion fromRegion;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "to_region_id", nullable = false)
	private SearchRegion toRegion;

	@Enumerated(EnumType.STRING)
	@Column(name = "relation_type", nullable = false, length = 30)
	private SearchRegionRelationType relationType;

	@Column(nullable = false, precision = 4, scale = 2)
	private BigDecimal weight;

	@Builder
	public SearchRegionRelation(SearchRegion fromRegion, SearchRegion toRegion,
			SearchRegionRelationType relationType, BigDecimal weight) {
		this.fromRegion = fromRegion;
		this.toRegion = toRegion;
		this.relationType = relationType;
		this.weight = weight != null ? weight : BigDecimal.ONE;
	}
}
