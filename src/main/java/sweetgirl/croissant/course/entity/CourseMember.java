package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

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

/**
 * 코스를 같이 편집하는 사람. 방장도 행을 갖는다(첫 초대 링크를 만들 때 생긴다).
 *
 * role 이 없다 — 전원 편집자다. 방장 여부는 courses.member_id 로만 판정한다.
 * 나갔다 다시 들어오면 같은 PK 라 새 행이 아니라 {@link #rejoin()} 으로 되살린다.
 * 사람 이름은 {@code member.nickname} 이다 — 코스 안 이름 짓기(display_name)는 2026-10-21 에 없앴다. 컬럼은 이 코드가
 * 운영에 깔린 뒤 다음 마이그레이션에서 지운다(블루그린 겹침 동안 옛 컨테이너가 그 컬럼을 읽는다).
 */
@Entity
@Table(name = "course_members")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseMember extends BaseTimeEntity {

	/** 방장 포함 정원. */
	public static final int MEMBER_LIMIT = 8;

	@EmbeddedId
	private CourseMemberId id;

	@MapsId("courseId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	/** 참여 순서의 기준 — 메모 정렬과 방장 승계 순서가 여기에 기댄다. */
	@Column(nullable = false)
	private LocalDateTime joinedAt;

	/** 나가기·탈퇴. */
	private LocalDateTime deletedAt;

	private CourseMember(Course course, Member member, LocalDateTime joinedAt) {
		this.id = new CourseMemberId(course.getId(), member.getId());
		this.course = course;
		this.member = member;
		this.joinedAt = joinedAt;
	}

	public static CourseMember of(Course course, Member member) {
		return new CourseMember(course, member, LocalDateTime.now());
	}

	public void leave() {
		this.deletedAt = LocalDateTime.now();
	}

	/** 나갔던 사람이 다시 들어온다. 참여 순서는 다시 들어온 시점으로 잡는다. */
	public void rejoin() {
		this.joinedAt = LocalDateTime.now();
		this.deletedAt = null;
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
