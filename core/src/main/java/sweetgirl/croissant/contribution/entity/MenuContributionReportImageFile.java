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

@Entity
@Table(name = "menu_contribution_report_image_file")
@SQLRestriction("processing_status = 'DONE'")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuContributionReportImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_contribution_report_id", nullable = false)
	private MenuContributionReport menuContributionReport;

	@Builder
	public MenuContributionReportImageFile(MenuContributionReport menuContributionReport, String name,
			MimeType mimeType, String awsKey) {
		super(name, mimeType, awsKey);
		this.menuContributionReport = menuContributionReport;
	}

	public MenuImageFile toMenuImage(Menu menu, String awsKey, int displayOrder) {
		return new MenuImageFile(menu, getName(), getMimeType(), awsKey, displayOrder, null);
	}
}
