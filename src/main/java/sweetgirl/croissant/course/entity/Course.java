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

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(nullable = false)
	private boolean isPublic;

	private LocalDateTime deletedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Course(
			CourseType type,
			String title,
			Member member,
			boolean isPublic) {
		this.type = type;
		this.title = title;
		this.member = member;
		this.isPublic = isPublic;
	}

	public static Course createCustom(
			String title,
			Member member) {
		return Course.builder()
			.type(CourseType.CUSTOM)
			.title(title)
			.member(member)
			.isPublic(false)
			.build();
	}

	public static Course createCurated(String title) {
		return Course.builder()
			.type(CourseType.CURATED)
			.title(title)
			.isPublic(true)
			.build();
	}

	public void updateTitle(String title) {
		this.title = title;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
