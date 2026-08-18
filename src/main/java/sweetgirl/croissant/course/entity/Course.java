package sweetgirl.croissant.course.entity;

import java.time.LocalDate;
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

	@Column(length = 30)
	private String badge;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(nullable = false)
	private boolean isPublic;

	@Column(name = "source_course_id")
	private Long sourceCourseId;

	@Column(name = "pin_order")
	private Integer pinOrder;

	/** 여행 계획 — 어느 지역(시·도)을 언제 가는지. 실제 방문(빵로그)과 대조하려고 만들 때 받는다. 구버전 앱은 null. */
	@Column(length = 20)
	private String region;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	private LocalDateTime deletedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Course(
			CourseType type,
			String title,
			Member member,
			boolean isPublic,
			String badge,
			Long sourceCourseId,
			String region,
			LocalDate startDate,
			LocalDate endDate) {
		this.type = type;
		this.title = title;
		this.member = member;
		this.isPublic = isPublic;
		this.badge = badge;
		this.sourceCourseId = sourceCourseId;
		this.region = region;
		this.startDate = startDate;
		this.endDate = endDate;
	}

	public static Course createCustom(
			String title,
			Member member,
			boolean isPublic) {
		return createCustom(title, member, isPublic, null, null, null);
	}

	public static Course createCustom(
			String title,
			Member member,
			boolean isPublic,
			String region,
			LocalDate startDate,
			LocalDate endDate) {
		return Course.builder()
			.type(CourseType.CUSTOM)
			.title(title)
			.member(member)
			.isPublic(isPublic)
			.region(region)
			.startDate(startDate)
			.endDate(endDate)
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

	public void updateRegion(String region) {
		this.region = region;
	}

	public void updateDates(LocalDate startDate, LocalDate endDate) {
		this.startDate = startDate;
		this.endDate = endDate;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public void pin(int pinOrder) {
		this.pinOrder = pinOrder;
	}

	public void unpin() {
		this.pinOrder = null;
	}

	public boolean isPinned() {
		return this.pinOrder != null;
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
