package sweetgirl.croissant.store.entity;

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
@Table(name = "store_image_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@Column(nullable = false, length = 255)
	private String name;

	@Builder
	public StoreImageFile(Store store, String name, MimeType mimeType, String awsKey) {
		super(name, mimeType, awsKey);
		this.store = store;
	}
}
