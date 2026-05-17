package sweetgirl.croissant.banner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.external.aws.enums.MimeType;

@Entity
@Table(name = "banner")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Banner extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 255)
	private String thumbnailName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private MimeType thumbnailMimeType;

	@Column(nullable = false, length = 255)
	private String thumbnailKey;

	@Column(nullable = false, length = 255)
	private String detailName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private MimeType detailMimeType;

	@Column(nullable = false, length = 255)
	private String detailKey;

	@Column(nullable = false)
	private int displayOrder;

	@Builder
	public Banner(String thumbnailName, MimeType thumbnailMimeType, String thumbnailKey,
			String detailName, MimeType detailMimeType, String detailKey, int displayOrder) {
		this.thumbnailName = thumbnailName;
		this.thumbnailMimeType = thumbnailMimeType;
		this.thumbnailKey = thumbnailKey;
		this.detailName = detailName;
		this.detailMimeType = detailMimeType;
		this.detailKey = detailKey;
		this.displayOrder = displayOrder;
	}

	public void updateDisplayOrder(int displayOrder) {
		this.displayOrder = displayOrder;
	}
}
