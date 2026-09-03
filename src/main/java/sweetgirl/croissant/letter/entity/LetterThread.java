package sweetgirl.croissant.letter.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

/**
 * 개발자와 회원 한 명 사이의 대화방. 회원당 하나뿐이다(member_id 유니크).
 *
 * <p>주제별로 쪼개지 않는 이유: 인터뷰 요청도 버그 제보도 결국 같은 사람과의 한 흐름이고,
 * 티켓으로 나누면 "저번에 얘기했던 거"를 찾으러 목록을 뒤져야 한다.
 */
@Entity
@Table(name = "letter_thread", uniqueConstraints = {
	@UniqueConstraint(columnNames = {"member_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LetterThread extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	/**
	 * 마지막 편지 시각. 어드민 목록의 정렬 기준이라 스레드가 들고 있는다 —
	 * 매번 letter_message를 group by 하면 대화가 늘수록 목록이 느려진다.
	 */
	@Column(name = "last_message_at", nullable = false)
	private LocalDateTime lastMessageAt;

	private LetterThread(Member member, LocalDateTime lastMessageAt) {
		this.member = member;
		this.lastMessageAt = lastMessageAt;
	}

	public static LetterThread create(Member member, LocalDateTime now) {
		return new LetterThread(member, now);
	}

	/** 편지가 오갈 때마다 맨 위로 올린다. */
	public void touch(LocalDateTime now) {
		this.lastMessageAt = now;
	}
}
