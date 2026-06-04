package sweetgirl.croissant.review.entity;

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
@Table(name = "review_image_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "review_id", nullable = false)
	private Review review;

	@Column(nullable = false)
	private int displayOrder;

	@Builder
	public ReviewImageFile(Review review, String name, MimeType mimeType, String awsKey, int displayOrder) {
		super(name, mimeType, awsKey);
		this.review = review;
		this.displayOrder = displayOrder;
	}
}
