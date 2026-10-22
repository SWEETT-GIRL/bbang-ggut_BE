package sweetgirl.croissant.comment.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/**
 * 빵로그 댓글. 수정은 없다(지우고 다시) — 본문이 불변이라 멘션 오프셋이 안 깨지고 신고 근거가 남는다.
 * 지운 댓글은 행을 남기고 {@code deletedBy} 로 누가 지웠는지만 적는다. 탈퇴한 사람의 댓글은 남고 작성자만 가린다.
 */
@Entity
@Table(name = "record_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecordComment extends BaseTimeEntity {

	public static final int CONTENT_MAX_LENGTH = 200;
	public static final int MAX_LINE_BREAKS = 3;
	public static final int MAX_MENTIONS = 3;

	public static final String DELETED_BY_AUTHOR = "AUTHOR";
	public static final String DELETED_BY_RECORD_OWNER = "RECORD_OWNER";
	public static final String DELETED_BY_ADMIN = "ADMIN";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "record_id", nullable = false)
	private BreadRecord record;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(nullable = false, length = CONTENT_MAX_LENGTH)
	private String content;

	private LocalDateTime deletedAt;

	@Column(length = 20)
	private String deletedBy;

	private LocalDateTime hiddenAt;

	@Column(length = 500)
	private String hiddenReason;

	@Column(length = 30)
	private String hiddenBy;

	private BreadRecordComment(BreadRecord record, Member member, String content) {
		this.record = record;
		this.member = member;
		this.content = content;
	}

	public static BreadRecordComment of(BreadRecord record, Member member, String content) {
		return new BreadRecordComment(record, member, content);
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}

	public boolean isHidden() {
		return this.hiddenAt != null;
	}

	public void delete(String deletedBy) {
		this.deletedAt = LocalDateTime.now();
		this.deletedBy = deletedBy;
	}

	public void hide(String reason, String hiddenBy) {
		this.hiddenAt = LocalDateTime.now();
		this.hiddenReason = reason;
		this.hiddenBy = hiddenBy;
	}

	public void restoreHidden() {
		this.hiddenAt = null;
		this.hiddenReason = null;
		this.hiddenBy = null;
	}
}
