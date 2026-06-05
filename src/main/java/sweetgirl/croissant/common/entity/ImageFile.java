package sweetgirl.croissant.common.entity;

import org.hibernate.annotations.ColumnDefault;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;

@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ImageFile extends BaseTimeEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 255)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private MimeType mimeType;

	@Column(nullable = false, length = 255)
	private String awsKey;

	@Enumerated(EnumType.STRING)
	@ColumnDefault("'DONE'")
	@Column(nullable = false, length = 20)
	private ImageStatus processingStatus;

	protected ImageFile(String name, MimeType mimeType, String awsKey) {
		this.name = name;
		this.mimeType = mimeType;
		this.awsKey = awsKey;
		this.processingStatus = ImageStatus.PENDING;
	}

	public void markDone() {
		this.processingStatus = ImageStatus.DONE;
	}

	public void markFailed() {
		this.processingStatus = ImageStatus.FAILED;
	}
}
