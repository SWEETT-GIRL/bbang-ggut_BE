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
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.course.enums.CourseMemberInviteStatus;
import sweetgirl.croissant.member.entity.Member;

/**
 * 앱 안 일행 초대 — 팔로잉 · 팔로워에서 골라 부른다. 링크 초대({@link CourseInvite})와 별개이고, 받는 사람이 [수락]/[거절]로 답한다.
 * 같은 사람을 같은 빵투어에 답하기 전엔 두 번 부르지 않는다(uk_course_member_invite_pending).
 */
@Entity
@Table(name = "course_member_invite")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseMemberInvite {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "inviter_id", nullable = false)
	private Member inviter;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "invitee_id", nullable = false)
	private Member invitee;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CourseMemberInviteStatus status;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "responded_at")
	private LocalDateTime respondedAt;

	private CourseMemberInvite(Course course, Member inviter, Member invitee, LocalDateTime now) {
		this.course = course;
		this.inviter = inviter;
		this.invitee = invitee;
		this.status = CourseMemberInviteStatus.PENDING;
		this.createdAt = now;
	}

	public static CourseMemberInvite of(Course course, Member inviter, Member invitee, LocalDateTime now) {
		return new CourseMemberInvite(course, inviter, invitee, now);
	}

	public void accept(LocalDateTime now) {
		this.status = CourseMemberInviteStatus.ACCEPTED;
		this.respondedAt = now;
	}

	public void decline(LocalDateTime now) {
		this.status = CourseMemberInviteStatus.DECLINED;
		this.respondedAt = now;
	}
}
