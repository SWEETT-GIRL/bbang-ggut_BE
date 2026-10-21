package sweetgirl.croissant.store.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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

@Entity
@Table(name = "search_region_alias", uniqueConstraints = {
	@UniqueConstraint(name = "uq_search_region_alias_normalized_alias", columnNames = "normalized_alias"),
	@UniqueConstraint(name = "uq_search_region_alias_region_alias", columnNames = {"region_id", "alias"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SearchRegionAlias {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "region_id", nullable = false)
	private SearchRegion region;

	@Column(nullable = false, length = 100)
	private String alias;

	@Column(name = "normalized_alias", nullable = false, length = 100)
	private String normalizedAlias;

	@Column(name = "is_primary", nullable = false)
	private Boolean isPrimary;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	public SearchRegionAlias(SearchRegion region, String alias, String normalizedAlias, Boolean isPrimary) {
		this.region = region;
		this.alias = alias;
		this.normalizedAlias = normalizedAlias;
		this.isPrimary = isPrimary != null ? isPrimary : false;
	}
}
