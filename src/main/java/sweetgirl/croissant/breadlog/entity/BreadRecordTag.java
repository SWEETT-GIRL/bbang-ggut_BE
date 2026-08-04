package sweetgirl.croissant.breadlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

@Entity
@Table(name = "bread_record_tag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecordTag extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "record_id", nullable = false)
	private BreadRecord record;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "photo_id", nullable = false)
	private BreadRecordPhoto photo;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 50)
	private String breadCategory;

	@Column(name = "x", nullable = false)
	private double positionX;

	@Column(name = "y", nullable = false)
	private double positionY;

	@Builder
	public BreadRecordTag(
			BreadRecord record,
			BreadRecordPhoto photo,
			String name,
			String breadCategory,
			double positionX,
			double positionY) {
		this.record = record;
		this.photo = photo;
		this.name = name;
		this.breadCategory = breadCategory;
		this.positionX = positionX;
		this.positionY = positionY;
	}
}
