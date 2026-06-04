package sweetgirl.croissant.store.entity;

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
import sweetgirl.croissant.external.aws.enums.MimeType;

@Entity
@Table(name = "store_image_file")
@SQLRestriction("image_status = 'DONE'")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@Column(nullable = false)
	private int displayOrder;

	@Builder
	public StoreImageFile(Store store, String name, MimeType mimeType, String awsKey, int displayOrder) {
		super(name, mimeType, awsKey);
		this.store = store;
		this.displayOrder = displayOrder;
	}

	public void updateDisplayOrder(int displayOrder) {
		this.displayOrder = displayOrder;
	}
}
