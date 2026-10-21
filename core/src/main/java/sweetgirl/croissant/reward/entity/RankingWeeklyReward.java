package sweetgirl.croissant.reward.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * 빵순이 랭킹 주간 보상 한 줄 — 지난주 전국 순위 1~10 위 가운데 한 사람.
 * 읽기 전용이다. 행은 {@code RankingWeeklyRewardRepository.insertIfAbsent} 로만 생긴다.
 */
@Entity
@Table(name = "ranking_weekly_rewards", uniqueConstraints = @UniqueConstraint(columnNames = {"week_start",
	"member_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RankingWeeklyReward {

	/** 받을 수 있게 된 월요일(순위를 겨룬 주의 다음 월요일)부터 4 주. 겨룬 주의 월요일에서는 35 일이다. */
	public static final int CLAIMABLE_DAYS = 35;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 순위를 겨룬 주의 월요일. */
	@Column(name = "week_start", nullable = false)
	private LocalDate weekStart;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "final_rank", nullable = false)
	private int finalRank;

	@Column(nullable = false)
	private int crumbs;

	/** 그 주의 점수(사진 있는 기록 수). 점수를 남기기 전에 만든 줄은 null */
	private Integer score;

	/** 1 위만 있다. 이날까지 왕관을 쓴다. */
	@Column(name = "crown_until")
	private LocalDate crownUntil;

	private LocalDateTime claimedAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public boolean isClaimed() {
		return claimedAt != null;
	}

	/** 이날 이후로는 받을 수 없다. */
	public LocalDate lastClaimableDay() {
		return weekStart.plusDays(CLAIMABLE_DAYS - 1L);
	}

	public boolean hasCrown() {
		return crownUntil != null;
	}
}
