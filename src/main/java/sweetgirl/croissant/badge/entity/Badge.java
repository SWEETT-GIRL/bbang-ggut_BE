package sweetgirl.croissant.badge.entity;

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
import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

@Entity
@Table(name = "badges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Badge extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 50)
	private String code;

	@Column(length = 255)
	private String imageKey;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 255)
	private String hint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ConditionType conditionType;

	private Integer threshold;

	@Column(nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active;

	private Badge(
			String code,
			String imageKey,
			String title,
			String hint,
			ConditionType conditionType,
			Integer threshold,
			int sortOrder) {
		this.code = code;
		this.imageKey = imageKey;
		this.title = title;
		this.hint = hint;
		this.conditionType = conditionType;
		this.threshold = threshold;
		this.sortOrder = sortOrder;
		this.active = true;
	}

	public static Badge create(
			String code,
			String imageKey,
			String title,
			String hint,
			ConditionType conditionType,
			Integer threshold,
			int sortOrder) {
		return new Badge(code, imageKey, title, hint, conditionType, threshold, sortOrder);
	}

	public void update(
			String imageKey,
			String title,
			String hint,
			ConditionType conditionType,
			Integer threshold,
			Integer sortOrder,
			Boolean active) {
		if (imageKey != null) {
			this.imageKey = imageKey;
		}
		if (title != null) {
			this.title = title;
		}
		if (hint != null) {
			this.hint = hint;
		}
		this.conditionType = conditionType;
		this.threshold = threshold;
		if (sortOrder != null) {
			this.sortOrder = sortOrder;
		}
		if (active != null) {
			this.active = active;
		}
	}

	public void deactivate() {
		this.active = false;
	}
}
