package sweetgirl.croissant.course.entity;

import java.time.LocalDate;
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

	/** 이 빵투어에서 처음 "다녀왔어요"를 누른 한국 날짜. 리워드의 날짜 판정이 본다. */
	@Column(name = "first_visited_on")
	private LocalDate firstVisitedOn;

	/** 모든 가게에 사진 붙은 기록이 달린 때. 사진 완주 판정을 한 번만 하기 위한 표시다. */
	@Column(name = "photo_completed_at")
	private LocalDateTime photoCompletedAt;

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

	/** 처음 다녀온 날을 남긴다. 이미 있으면 덮지 않는다. */
	public void markFirstVisit(LocalDate visitedOn) {
		if (this.firstVisitedOn == null) {
			this.firstVisitedOn = visitedOn;
		}
	}

	public void markPhotoCompleted(LocalDateTime photoCompletedAt) {
		if (this.photoCompletedAt == null) {
			this.photoCompletedAt = photoCompletedAt;
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
