package sweetgirl.croissant.breadlog.entity;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/**
 * 빵로그 랭킹 "내 지역" — 회원이 직접 고른 시·도.
 *
 * <p>앱이 지역을 보내게 하지 않고 서버가 들고 있는 이유: 여러 지역 판을 돌아가며 보지 못하게 하려는 것이다.
 * 바꾸기는 {@link #COOLDOWN} 에 한 번이고, 처음 정한 뒤 {@link #FREE_FIX} 안의 한 번은 잘못 누른 것으로 보고 봐준다.
 *
 * <p>{@code member} 를 연관 관계로 잡지 않은 이유는 {@code MemberWorld} 와 같다 — 회원 id 만 있으면 된다.
 */
@Entity
@Table(name = "member_ranking_region")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberRankingRegion extends BaseTimeEntity {

	public static final Duration COOLDOWN = Duration.ofDays(30);
	public static final Duration FREE_FIX = Duration.ofHours(24);

	@Id
	@Column(name = "member_id")
	private Long memberId;

	/** 짧은 시·도(서울·경기…) — bread_record.region_key 첫 칸과 같은 표기. */
	@Column(nullable = false, length = 10)
	private String sido;

	/** 마지막으로 정하거나 바꾼 시각(Asia/Seoul). */
	@Column(nullable = false)
	private LocalDateTime changedAt;

	/** 처음 정한 것도 1 번으로 센다. */
	@Column(nullable = false)
	private int changeCount;

	private MemberRankingRegion(Long memberId, String sido, LocalDateTime now) {
		this.memberId = memberId;
		this.sido = sido;
		this.changedAt = now;
		this.changeCount = 1;
	}

	public static MemberRankingRegion first(Long memberId, String sido, LocalDateTime now) {
		return new MemberRankingRegion(memberId, sido, now);
	}

	/**
	 * 다음에 바꿀 수 있는 시각. 지금 바꿀 수 있으면 null.
	 *
	 * <p>처음 정하고 하루 안이면 한 번은 바로 바꿀 수 있다. 그 뒤로는 마지막으로 바꾼 때부터 30일.
	 */
	public LocalDateTime nextChangeableAt(LocalDateTime now) {
		if (changeCount == 1 && now.isBefore(changedAt.plus(FREE_FIX))) {
			return null;
		}
		LocalDateTime next = changedAt.plus(COOLDOWN);
		return now.isBefore(next) ? next : null;
	}

	/** 바꿀 수 있는지는 서비스가 {@link #nextChangeableAt} 로 먼저 확인한다. */
	public void change(String sido, LocalDateTime now) {
		this.sido = sido;
		this.changedAt = now;
		this.changeCount++;
	}
}
