package sweetgirl.croissant.external.aws.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.common.enums.ImageStatus;

@Entity
@Table(name = "image_processing_callback")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageProcessingCallback extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "aws_key", nullable = false, length = 255)
	private String awsKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ImageStatus status;

	@Column(name = "fail_reason", length = 1000)
	private String failReason;

	public ImageProcessingCallback(String awsKey, ImageStatus status, String failReason) {
		this.awsKey = awsKey;
		this.status = status;
		this.failReason = failReason;
	}

	public void update(ImageStatus status, String failReason) {
		this.status = status;
		this.failReason = failReason;
	}
}
