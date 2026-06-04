package sweetgirl.croissant.common.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
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
import sweetgirl.croissant.external.aws.enums.MimeType;

@MappedSuperclass
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public abstract class ImageFile {
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
	private ImageStatus imageStatus;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected ImageFile(String name, MimeType mimeType, String awsKey) {
		this.name = name;
		this.mimeType = mimeType;
		this.awsKey = awsKey;
		this.imageStatus = ImageStatus.PENDING;
	}

	public void markDone() {
		this.imageStatus = ImageStatus.DONE;
	}

	public void markFailed() {
		this.imageStatus = ImageStatus.FAILED;
	}
}
