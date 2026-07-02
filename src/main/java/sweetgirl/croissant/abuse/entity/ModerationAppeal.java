package sweetgirl.croissant.abuse.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.abuse.enums.ModerationAppealStatus;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "moderation_appeal", uniqueConstraints = @UniqueConstraint(columnNames = {
	"moderation_action_id", "member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModerationAppeal extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "moderation_action_id", nullable = false)
	private ModerationAction moderationAction;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false, length = 1000)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ModerationAppealStatus status;

	@Column(name = "admin_memo", length = 1000)
	private String adminMemo;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	@Builder
	public ModerationAppeal(
			ModerationAction moderationAction,
			Member member,
			String content) {
		this.moderationAction = moderationAction;
		this.member = member;
		this.content = content;
		this.status = ModerationAppealStatus.PENDING;
	}

	public boolean isPending() {
		return status == ModerationAppealStatus.PENDING;
	}

	public void accept(String adminMemo) {
		this.status = ModerationAppealStatus.ACCEPTED;
		this.adminMemo = adminMemo;
		this.reviewedAt = LocalDateTime.now();
	}

	public void reject(String adminMemo) {
		this.status = ModerationAppealStatus.REJECTED;
		this.adminMemo = adminMemo;
		this.reviewedAt = LocalDateTime.now();
	}
}
