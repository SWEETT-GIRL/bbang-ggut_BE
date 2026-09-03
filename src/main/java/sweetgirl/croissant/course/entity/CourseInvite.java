package sweetgirl.croissant.course.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import sweetgirl.croissant.member.entity.Member;

/**
 * 코스 초대 링크. 토큰을 아는 사람이 수락하면 편집자가 된다.
 *
 * 코스당 살아있는 링크는 항상 1개 — 재생성하면 이전 링크는 {@link #revoke()} 로 무효화한다.
 * 만료는 expires_at 으로 판정하고 행은 지우지 않는다.
 * 테이블에 updated_at 이 없어 BaseTimeEntity 를 상속하지 않는다.
 */
@Entity
@Table(name = "course_invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class CourseInvite {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@Column(nullable = false, unique = true, length = 64)
	private String token;

	/** 만든 사람(방장 또는 멤버). 게스트 이관 대상이다. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by", nullable = false)
	private Member createdBy;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 재생성으로 무효화된 시각. */
	private LocalDateTime deletedAt;

	private CourseInvite(Course course, Member createdBy, String token, LocalDateTime expiresAt) {
		this.course = course;
		this.createdBy = createdBy;
		this.token = token;
		this.expiresAt = expiresAt;
	}

	public static CourseInvite of(Course course, Member createdBy, String token, LocalDateTime expiresAt) {
		return new CourseInvite(course, createdBy, token, expiresAt);
	}

	public boolean isExpired(LocalDateTime now) {
		return !this.expiresAt.isAfter(now);
	}

	public boolean isActive(LocalDateTime now) {
		return this.deletedAt == null && !isExpired(now);
	}

	public void revoke() {
		this.deletedAt = LocalDateTime.now();
	}
}
