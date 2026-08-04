package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
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

	// 구버전 인스턴스와 롤백 호환을 위해 상태 전환과 함께 갱신하는 legacy 컬럼이다.
	private LocalDateTime completedAt;

	private LocalDateTime stoppedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CourseProgressStatus status;

	private LocalDateTime finishedAt;

	private CourseProgress(Member member, Course course, LocalDateTime startedAt) {
		this.id = new CourseProgressId(member.getId(), course.getId());
		this.member = member;
		this.course = course;
		this.startedAt = startedAt;
		this.status = CourseProgressStatus.IN_PROGRESS;
	}

	public static CourseProgress start(Member member, Course course, LocalDateTime startedAt) {
		return new CourseProgress(member, course, startedAt);
	}

	public void resume() {
		if (this.status == CourseProgressStatus.STOPPED) {
			this.status = CourseProgressStatus.IN_PROGRESS;
			this.stoppedAt = null;
		}
	}

	public void stop(LocalDateTime stoppedAt) {
		if (this.status == CourseProgressStatus.IN_PROGRESS) {
			this.status = CourseProgressStatus.STOPPED;
			this.stoppedAt = stoppedAt;
		}
	}

	public void markFinished(LocalDateTime finishedAt) {
		if (this.status == CourseProgressStatus.IN_PROGRESS) {
			this.status = CourseProgressStatus.FINISHED;
			this.finishedAt = finishedAt;
			this.completedAt = finishedAt;
			this.stoppedAt = null;
		}
	}
}
