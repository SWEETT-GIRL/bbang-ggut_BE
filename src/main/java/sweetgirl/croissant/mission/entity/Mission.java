package sweetgirl.croissant.mission.entity;

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
@Table(name = "missions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mission extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 50)
	private String code;

	@Column(nullable = false, length = 100)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ConditionType conditionType;

	private Integer threshold;

	@Column(nullable = false, length = 255)
	private String actionDeeplink;

	@Column(length = 50)
	private String badgeCode;

	@Column(nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active;

	private Mission(
			String code,
			String title,
			ConditionType conditionType,
			Integer threshold,
			String actionDeeplink,
			String badgeCode,
			int sortOrder) {
		this.code = code;
		this.title = title;
		this.conditionType = conditionType;
		this.threshold = threshold;
		this.actionDeeplink = actionDeeplink;
		this.badgeCode = badgeCode;
		this.sortOrder = sortOrder;
		this.active = true;
	}

	public static Mission create(
			String code,
			String title,
			ConditionType conditionType,
			Integer threshold,
			String actionDeeplink,
			String badgeCode,
			int sortOrder) {
		return new Mission(code, title, conditionType, threshold, actionDeeplink, badgeCode, sortOrder);
	}

	public void update(
			String title,
			ConditionType conditionType,
			Integer threshold,
			String actionDeeplink,
			String badgeCode,
			Integer sortOrder,
			Boolean active) {
		if (title != null) {
			this.title = title;
		}
		this.conditionType = conditionType;
		this.threshold = threshold;
		if (actionDeeplink != null) {
			this.actionDeeplink = actionDeeplink;
		}
		this.badgeCode = badgeCode;
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
