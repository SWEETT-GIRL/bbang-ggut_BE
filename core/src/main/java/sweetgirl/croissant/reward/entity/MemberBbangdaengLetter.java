package sweetgirl.croissant.reward.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원이 받은 빵댕이 편지 한 통. 한 주에 한 통이다.
 *
 * <p>안 연 편지는 늘 가장 최근 주에 놓인다 — 오래 안 들어온 회원의 안 연 편지는 주만 앞으로 옮겨진다({@link #moveTo}).
 */
@Entity
@Table(name = "member_bbangdaeng_letters", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id",
	"week_start"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberBbangdaengLetter {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "letter_id", nullable = false)
	private Long letterId;

	/** 그 주 월요일(한국 날짜). */
	@Column(name = "week_start", nullable = false)
	private LocalDate weekStart;

	/** 보이기 시작하는 날. 가입한 주의 첫 통만 가입 다음 날이고, 나머지는 그 주 월요일이다. */
	@Column(name = "visible_from", nullable = false)
	private LocalDate visibleFrom;

	private LocalDateTime openedAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private MemberBbangdaengLetter(
			Long memberId, Long letterId, LocalDate weekStart, LocalDate visibleFrom, LocalDateTime createdAt) {
		this.memberId = memberId;
		this.letterId = letterId;
		this.weekStart = weekStart;
		this.visibleFrom = visibleFrom;
		this.createdAt = createdAt;
	}

	public static MemberBbangdaengLetter deliver(
			Long memberId, Long letterId, LocalDate weekStart, LocalDate visibleFrom, LocalDateTime createdAt) {
		return new MemberBbangdaengLetter(memberId, letterId, weekStart, visibleFrom, createdAt);
	}

	public boolean isOpened() {
		return openedAt != null;
	}

	/** 안 연 편지를 더 최근 주로 옮긴다. 옮긴 편지는 그 주 월요일부터 보인다. */
	public void moveTo(LocalDate weekStart) {
		this.weekStart = weekStart;
		this.visibleFrom = weekStart;
	}
}
