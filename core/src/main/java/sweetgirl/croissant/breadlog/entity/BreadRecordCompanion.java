package sweetgirl.croissant.breadlog.entity;

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
import sweetgirl.croissant.member.entity.Member;

/**
 * 빵로그에 같이 간 사람으로 태그된 회원. 태그된 사람이 스스로 빼면 {@code removedAt} 을 채우고 행은 남긴다 —
 * 같은 기록에 다시 태그하지 못하게. 작성자가 뺀 건 행을 지운다(다시 넣을 수 있다).
 */
@Entity
@Table(name = "record_companion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecordCompanion {

	@EmbeddedId
	private BreadRecordCompanionId id;

	@MapsId("recordId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "record_id", nullable = false)
	private BreadRecord record;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "removed_at")
	private LocalDateTime removedAt;

	private BreadRecordCompanion(BreadRecord record, Member member, LocalDateTime now) {
		this.id = new BreadRecordCompanionId(record.getId(), member.getId());
		this.record = record;
		this.member = member;
		this.createdAt = now;
	}

	public static BreadRecordCompanion of(BreadRecord record, Member member, LocalDateTime now) {
		return new BreadRecordCompanion(record, member, now);
	}

	public boolean isRemoved() {
		return removedAt != null;
	}

	/** 태그된 사람이 스스로 뺀다. 이미 뺐으면 그대로. */
	public void removeBySelf(LocalDateTime now) {
		if (removedAt == null) {
			removedAt = now;
		}
	}
}
