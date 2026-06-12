package sweetgirl.croissant.contribution.entity;

import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.ImageFile;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;

@Entity
@Table(name = "store_contribution_report_image_file")
@SQLRestriction("processing_status = 'DONE'")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreContributionReportImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_contribution_report_id", nullable = false)
	private StoreContributionReport storeContributionReport;

	@Builder
	public StoreContributionReportImageFile(StoreContributionReport storeContributionReport, String name,
			MimeType mimeType, String awsKey) {
		super(name, mimeType, awsKey);
		this.storeContributionReport = storeContributionReport;
	}

	public StoreImageFile toStoreImage(Store store, String awsKey, int displayOrder) {
		return new StoreImageFile(store, getName(), getMimeType(), awsKey, displayOrder, null);
	}

	public MenuImageFile toMenuImage(Menu menu, String awsKey, int displayOrder) {
		return new MenuImageFile(menu, getName(), getMimeType(), awsKey, displayOrder, null);
	}
}
