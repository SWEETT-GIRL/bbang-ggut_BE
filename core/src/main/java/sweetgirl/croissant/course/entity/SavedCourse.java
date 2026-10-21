package sweetgirl.croissant.course.entity;

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
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "saved_courses", uniqueConstraints = {
	@UniqueConstraint(columnNames = {"member_id", "course_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedCourse extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@Builder(access = AccessLevel.PRIVATE)
	private SavedCourse(
			Member member,
			Course course) {
		this.member = member;
		this.course = course;
	}

	public static SavedCourse create(
			Member member,
			Course course) {
		return SavedCourse.builder()
			.member(member)
			.course(course)
			.build();
	}
}
