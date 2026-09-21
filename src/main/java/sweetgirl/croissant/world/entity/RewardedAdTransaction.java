package sweetgirl.croissant.world.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보상형 광고 콜백을 받은 이력. {@code transactionId} 가 PK 라, 구글이 같은 콜백을 다시 보내도
 * 두 번째 삽입이 DB 에서 막힌다 — 중복 적립을 코드가 아니라 DB 가 막는다.
 */
@Entity
@Table(name = "rewarded_ad_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RewardedAdTransaction {

	@Id
	@Column(name = "transaction_id", length = 64)
	private String transactionId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "character_code", nullable = false, length = 30)
	private String characterCode;

	/** 어느 광고 단위에서 났는가. 광고 수익과 대조할 때 쓴다. */
	@Column(length = 64)
	private String adUnit;

	private Integer rewardAmount;

	@Column(length = 64)
	private String rewardItem;

	@Column(nullable = false)
	private LocalDateTime createdAt;
}
