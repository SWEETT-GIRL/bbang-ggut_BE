package sweetgirl.croissant.alarm.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.member.entity.Member;

/**
 * 누가 내 빵투어를 찜했는지 알리는 알림 한 줄.
 *
 * <p>owner는 코스 주인(알림을 받는 사람), actor는 찜한 사람이다. 자기 코스 찜은
 * {@code SavedCourseCommandService}가 이미 막으므로 둘이 같은 경우는 없다.
 *
 * <p>(actor, course)가 유니크라 껐다 켰다 반복해도 한 줄이다. 다시 찜하면
 * {@link #renew()}로 시각만 갱신해 맨 위로 올린다.
 */
@Entity
@Table(name = "course_save_notification", uniqueConstraints = {
	@UniqueConstraint(columnNames = {"actor_id", "course_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseSaveNotification extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "owner_id", nullable = false)
	private Member owner;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_id", nullable = false)
	private Member actor;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	/**
	 * 목록 정렬 기준. BaseTimeEntity의 createdAt은 @CreatedDate라 한 번 박히면 안 바뀌는데,
	 * 다시 찜한 알림은 맨 위로 올라가야 해서 직접 들고 있는 시각이 따로 필요하다.
	 */
	@Column(name = "notified_at", nullable = false)
	private LocalDateTime notifiedAt;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	@Builder(access = AccessLevel.PRIVATE)
	private CourseSaveNotification(Member owner, Member actor, Course course, LocalDateTime notifiedAt) {
		this.owner = owner;
		this.actor = actor;
		this.course = course;
		this.notifiedAt = notifiedAt;
	}

	public static CourseSaveNotification create(Member owner, Member actor, Course course, LocalDateTime notifiedAt) {
		return CourseSaveNotification.builder()
			.owner(owner)
			.actor(actor)
			.course(course)
			.notifiedAt(notifiedAt)
			.build();
	}

	/**
	 * 찜을 해제했다가 다시 한 경우. 새 줄을 만들지 않고 안 읽음으로 되돌린다 —
	 * 주인 입장에선 방금 일어난 일이라 목록 맨 위에 있어야 한다.
	 */
	public void renew(LocalDateTime now) {
		this.notifiedAt = now;
		this.readAt = null;
	}

	public void markAsRead(LocalDateTime now) {
		if (this.readAt == null) {
			this.readAt = now;
		}
	}

	public boolean isRead() {
		return this.readAt != null;
	}
}
