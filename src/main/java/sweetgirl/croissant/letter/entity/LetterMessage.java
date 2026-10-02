package sweetgirl.croissant.letter.entity;

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
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;

/**
 * 편지 한 통.
 *
 * <p>{@code readAt}은 <b>받는 쪽</b>이 읽은 시각이다. 보낸 사람은 자기 편지를 읽을 일이 없다.
 * sender=ADMIN이면 사용자가 읽은 시각, sender=MEMBER면 어드민이 읽은 시각이라 컬럼 하나로 족하다.
 */
@Entity
@Table(name = "letter_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LetterMessage extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "thread_id", nullable = false)
	private LetterThread thread;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private LetterSender sender;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String body;

	/**
	 * 문의 유형. 사용자가 말을 걸며 고른 값이고, 이어지는 메시지에도 앱이 같은 값을 실어 보낸다.
	 * 어드민 답장에는 없다 — 답장은 그 건에 딸린 것이라 유형을 다시 붙일 이유가 없다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private LetterCategory category;

	/** 사용자가 첨부한 사진의 S3 키. 사진 없는 편지가 대부분이라 nullable. */
	@Column(name = "image_key", length = 512)
	private String imageKey;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	private LetterMessage(LetterThread thread, LetterSender sender, String body, LetterCategory category) {
		this.thread = thread;
		this.sender = sender;
		this.body = body;
		this.category = category;
	}

	public static LetterMessage create(LetterThread thread, LetterSender sender, String body) {
		return new LetterMessage(thread, sender, body, null);
	}

	public static LetterMessage create(LetterThread thread, LetterSender sender, String body,
			LetterCategory category) {
		return new LetterMessage(thread, sender, body, category);
	}

	/** 사진이 붙은 편지. 사진만 보낼 땐 body가 빈 문자열이다 — 컬럼이 NOT NULL이라. */
	public static LetterMessage create(LetterThread thread, LetterSender sender, String body,
			LetterCategory category, String imageKey) {
		LetterMessage message = new LetterMessage(thread, sender, body, category);
		message.imageKey = imageKey;
		return message;
	}

	public void markAsRead(LocalDateTime now) {
		if (this.readAt == null) {
			this.readAt = now;
		}
	}

	public boolean isRead() {
		return this.readAt != null;
	}
}
