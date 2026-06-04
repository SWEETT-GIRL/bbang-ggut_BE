package sweetgirl.croissant.menu.entity;

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

@Entity
@Table(name = "menu_add_report_image_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuAddReportImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "menu_add_report_id", nullable = false)
	private MenuAddReport menuAddReport;

	@Builder
	public MenuAddReportImageFile(MenuAddReport menuAddReport, String name, MimeType mimeType,
			String awsKey) {
		super(name, mimeType, awsKey);
		this.menuAddReport = menuAddReport;
	}
}
