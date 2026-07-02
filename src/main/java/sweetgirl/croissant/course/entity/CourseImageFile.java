package sweetgirl.croissant.course.entity;

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
@Table(name = "course_image_files")
@SQLRestriction("processing_status = 'DONE'")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseImageFile extends ImageFile {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@Column(nullable = false)
	private int displayOrder;

	@Builder
	public CourseImageFile(
			Course course,
			String name,
			MimeType mimeType,
			String awsKey,
			int displayOrder) {
		super(name, mimeType, awsKey);
		this.course = course;
		this.displayOrder = displayOrder;
	}

	public void updateDisplayOrder(int displayOrder) {
		this.displayOrder = displayOrder;
	}
}
