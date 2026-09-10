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

	/**
	 * 어드민이 이 대화를 해결로 닫은 시각. NULL이면 진행 중이다.
	 *
	 * <p>앱이 "어떤 이야기인지 골라주세요"를 다시 띄울지가 이 값으로 갈린다. 화면 지역 상태로
	 * 두면 상담 중에 잠깐 나갔다 와도 처음부터 다시 시작하는 것처럼 보인다 — 그 판단은
	 * 사용자의 화면이 아니라 대화 자체가 들고 있어야 한다.
	 *
	 * <p>boolean이 아니라 시각인 건, 닫힌 뒤 얼마 만에 다시 말을 걸었는지가 나중에 볼 값이라서다.
	 */
	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	/**
	 * 사용자가 이 대화에 마지막으로 편지를 쓴 시각. NULL이면 아직 한 번도 말을 걸지 않았다.
	 *
	 * <p>어드민 편지함은 이 값이 있는 대화만 보여준다. 가입 인사도 일괄 발신 공지도 대화방을
	 * 만들거나 맨 위로 올리는데, 그건 우리가 보낸 말이지 답할 일이 아니다 — 회원이 늘수록
	 * 그런 줄이 실제 문의를 목록 밖으로 밀어낸다.
	 *
	 * <p>{@code lastMessageAt}과 따로 두는 이유: 어드민이 답장하면 마지막 편지 시각은 갱신되지만
	 * 사용자가 말을 건 때는 그대로여야 한다. 두 값이 "언제 올라왔나"와 "누가 시작했나"를
	 * 나눠 들고 있다.
	 */
	@Column(name = "last_member_message_at")
	private LocalDateTime lastMemberMessageAt;

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

	/**
	 * 사용자가 말을 걸었다. 이때부터 이 대화는 어드민 편지함 목록에 오른다.
	 *
	 * <p>한 번 오른 대화는 내려가지 않는다 — 해결로 닫아도 지난 문의는 목록에 남아야 하고,
	 * 같은 사람이 다음에 다시 말을 걸 자리도 그 방이다.
	 */
	public void touchMember(LocalDateTime now) {
		this.lastMemberMessageAt = now;
	}

	/** 어드민이 해결로 닫는다. 이미 닫혀 있으면 시각을 덮지 않는다 — 처음 닫은 때가 사실이다. */
	public void resolve(LocalDateTime now) {
		if (this.resolvedAt == null) {
			this.resolvedAt = now;
		}
	}

	/**
	 * 다시 연다. 편지가 한 통이라도 새로 오가면 그 대화는 이어지는 중이다 —
	 * 사용자가 보내든, 어드민이 답장하든, 어드민이 먼저 말을 걸든 마찬가지다.
	 */
	public void reopen() {
		this.resolvedAt = null;
	}

	public boolean isResolved() {
		return this.resolvedAt != null;
	}
}
