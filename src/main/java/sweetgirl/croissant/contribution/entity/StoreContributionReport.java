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
import sweetgirl.croissant.store.entity.StoreAddReport;
import sweetgirl.croissant.store.entity.StoreReport;

@Entity
@Table(name = "store_contribution_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreContributionReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "contribution_report_id", nullable = false)
	private ContributionReport contributionReport;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_report_id")
	private StoreReport storeReport;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_add_report_id")
	private StoreAddReport storeAddReport;

	@Builder
	public StoreContributionReport(
			ContributionReport contributionReport,
			StoreReport storeReport,
			StoreAddReport storeAddReport) {
		this.contributionReport = contributionReport;
		this.storeReport = storeReport;
		this.storeAddReport = storeAddReport;
	}
}
