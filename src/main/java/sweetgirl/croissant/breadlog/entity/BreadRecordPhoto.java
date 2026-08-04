package sweetgirl.croissant.breadlog.entity;

import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
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
@Table(name = "bread_record_photo")
@SQLRestriction("processing_status = 'DONE'")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecordPhoto extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "record_id", nullable = false)
	private BreadRecord record;

	@Column(nullable = false)
	private int sortOrder;

	@Builder
	public BreadRecordPhoto(
			BreadRecord record,
			String name,
			MimeType mimeType,
			String awsKey,
			int sortOrder) {
		super(name, mimeType, awsKey);
		this.record = record;
		this.sortOrder = sortOrder;
	}
}
