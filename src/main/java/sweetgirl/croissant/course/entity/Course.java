package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "courses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CourseType type;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false)
	private int likeCount;

	@Column(length = 30)
	private String badge;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(nullable = false)
	private boolean isPublic;

	@Column(name = "source_course_id")
	private Long sourceCourseId;

	private LocalDateTime deletedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Course(
			CourseType type,
			String title,
			Member member,
			boolean isPublic,
			String badge,
			Long sourceCourseId) {
		this.type = type;
		this.title = title;
		this.member = member;
		this.isPublic = isPublic;
		this.badge = badge;
		this.sourceCourseId = sourceCourseId;
		this.likeCount = 0;
	}

	public static Course createCustom(
			String title,
			Member member,
			boolean isPublic) {
		return Course.builder()
			.type(CourseType.CUSTOM)
			.title(title)
			.member(member)
			.isPublic(isPublic)
			.build();
	}

	public static Course createCurated(
			String title,
			String badge) {
		return Course.builder()
			.type(CourseType.CURATED)
			.title(title)
			.isPublic(true)
			.badge(badge)
			.build();
	}

	public static Course createForked(
			String title,
			Member member,
			Long sourceCourseId) {
		return Course.builder()
			.type(CourseType.FORKED)
			.title(title)
			.member(member)
			.isPublic(false)
			.sourceCourseId(sourceCourseId)
			.build();
	}

	public void updateTitle(String title) {
		this.title = title;
	}

	public void updatePublic(boolean isPublic) {
		this.isPublic = isPublic;
	}

	public void updateBadge(String badge) {
		this.badge = badge;
	}

	public void increaseLikeCount() {
		this.likeCount++;
	}

	public void decreaseLikeCount() {
		if (this.likeCount > 0) {
			this.likeCount--;
		}
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
