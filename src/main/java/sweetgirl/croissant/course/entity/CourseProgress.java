package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "course_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseProgress extends BaseTimeEntity {

	@EmbeddedId
	private CourseProgressId id;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@MapsId("courseId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@Column(nullable = false)
	private LocalDateTime startedAt;

	private LocalDateTime completedAt;

	private LocalDateTime stoppedAt;

	private CourseProgress(Member member, Course course, LocalDateTime startedAt) {
		this.id = new CourseProgressId(member.getId(), course.getId());
		this.member = member;
		this.course = course;
		this.startedAt = startedAt;
	}

	public static CourseProgress start(Member member, Course course, LocalDateTime startedAt) {
		return new CourseProgress(member, course, startedAt);
	}

	public void resume() {
		this.stoppedAt = null;
	}

	public void stop(LocalDateTime stoppedAt) {
		this.stoppedAt = stoppedAt;
	}

	public void markCompleted(LocalDateTime completedAt) {
		if (this.completedAt == null) {
			this.completedAt = completedAt;
		}
	}

	public void markIncomplete() {
		this.completedAt = null;
	}
}
