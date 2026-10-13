package sweetgirl.croissant.reward.entity;

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
 * 앱이 아직 연출하지 못한 보상. 초대 성사·랭킹처럼 요청 밖에서 생긴 것과, 연출을 못 보고 앱이 꺼진 레벨업을 담는다.
 *
 * <p>앱이 연출을 끝내고 알리면(ack) 행을 지운다. 지급 이력은 원장에 남으므로 여기에는 남기지 않는다.
 */
@Entity
@Table(name = "member_pending_rewards", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id",
	"dedupe_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPendingReward {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	/** 같은 연출이 두 번 쌓이지 않게 하는 키(level_up:3 …). */
	@Column(name = "dedupe_key", nullable = false, length = 60)
	private String dedupeKey;

	/** 응답의 {@code rewards[]} 한 칸과 같은 모양의 JSON. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String payload;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private MemberPendingReward(Long memberId, String dedupeKey, String payload, LocalDateTime createdAt) {
		this.memberId = memberId;
		this.dedupeKey = dedupeKey;
		this.payload = payload;
		this.createdAt = createdAt;
	}

	public static MemberPendingReward of(Long memberId, String dedupeKey, String payload, LocalDateTime createdAt) {
		return new MemberPendingReward(memberId, dedupeKey, payload, createdAt);
	}
}
