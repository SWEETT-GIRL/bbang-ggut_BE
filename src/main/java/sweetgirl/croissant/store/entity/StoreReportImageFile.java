package sweetgirl.croissant.store.entity;

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
import sweetgirl.croissant.external.aws.enums.MimeType;

@Entity
@Table(name = "store_report_image_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreReportImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "request_id", nullable = false)
	private StoreReport storeReport;

	@Builder
	public StoreReportImageFile(StoreReport storeReport, String name, MimeType mimeType, String awsKey) {
		super(name, mimeType, awsKey);
		this.storeReport = storeReport;
	}
}
