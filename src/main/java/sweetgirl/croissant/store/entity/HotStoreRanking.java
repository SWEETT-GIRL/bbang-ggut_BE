package sweetgirl.croissant.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.menu.entity.FamousMenuByRegion;

@Entity
@Table(name = "hot_store_ranking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HotStoreRanking {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "famous_menu_by_region_id", nullable = false)
	private FamousMenuByRegion famousMenuByRegion;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@Column(nullable = false)
	private int rank;
}
