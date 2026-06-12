package sweetgirl.croissant.contribution.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.store.entity.StoreReport;

@Entity
@Table(name = "menu_contribution_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuContributionReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "contribution_report_id", nullable = false)
	private ContributionReport contributionReport;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_add_report_id")
	private MenuAddReport menuAddReport;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_report_id")
	private StoreReport storeReport;

	@Builder
	public MenuContributionReport(
			ContributionReport contributionReport,
			MenuAddReport menuAddReport,
			StoreReport storeReport) {
		this.contributionReport = contributionReport;
		this.menuAddReport = menuAddReport;
		this.storeReport = storeReport;
	}
}
