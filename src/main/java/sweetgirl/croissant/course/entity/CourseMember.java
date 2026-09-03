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

/**
 * 코스를 같이 편집하는 사람. 방장도 행을 갖는다(첫 초대 링크를 만들 때 생긴다).
 *
 * role 이 없다 — 전원 편집자다. 방장 여부는 courses.member_id 로만 판정한다.
 * 나갔다 다시 들어오면 같은 PK 라 새 행이 아니라 {@link #rejoin(String)} 으로 되살린다.
 */
@Entity
@Table(name = "course_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseMember extends BaseTimeEntity {

	/** 코스 안에서 쓰는 이름의 최대 길이. DB VARCHAR(10) 과 요청 DTO 의 @Size 가 같은 값을 본다. */
	public static final int DISPLAY_NAME_MAX_LENGTH = 10;

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

	@Column(nullable = false, length = DISPLAY_NAME_MAX_LENGTH)
	private String displayName;

	/** 참여 순서의 기준 — 메모 정렬과 방장 승계 순서가 여기에 기댄다. */
	@Column(nullable = false)
	private LocalDateTime joinedAt;

	/** 나가기·탈퇴. */
	private LocalDateTime deletedAt;

	private CourseMember(Course course, Member member, String displayName, LocalDateTime joinedAt) {
		this.id = new CourseMemberId(course.getId(), member.getId());
		this.course = course;
		this.member = member;
		this.displayName = displayName;
		this.joinedAt = joinedAt;
	}

	public static CourseMember of(Course course, Member member, String displayName) {
		return new CourseMember(course, member, displayName, LocalDateTime.now());
	}

	public void rename(String displayName) {
		this.displayName = displayName;
	}

	public void leave() {
		this.deletedAt = LocalDateTime.now();
	}

	/** 나갔던 사람이 다시 들어온다. 참여 순서는 다시 들어온 시점으로 잡는다. */
	public void rejoin(String displayName) {
		this.displayName = displayName;
		this.joinedAt = LocalDateTime.now();
		this.deletedAt = null;
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
