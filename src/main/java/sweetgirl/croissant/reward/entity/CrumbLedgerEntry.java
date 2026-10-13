package sweetgirl.croissant.reward.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;

/**
 * 부스러기 원장의 한 줄. 읽기 전용이다 — 행은 {@code CrumbLedgerRepository.insertIfAbsent} 로만 생긴다.
 *
 * <p>{@code (memberId, idempotencyKey)} 유니크가 같은 보상의 중복 지급을 막는다.
 */
@Entity
@Table(name = "crumb_ledger", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrumbLedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	/** 적립은 양수, 차감은 음수. */
	@Column(nullable = false)
	private int amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private CrumbReason reason;

	@Column(name = "idempotency_key", nullable = false, length = 100)
	private String idempotencyKey;

	@Column(nullable = false)
	private int balanceAfter;

	/** 한국 날짜. 하루 상한과 "오늘 받은 양"이 이 값으로 센다. */
	@Column(name = "earned_on", nullable = false)
	private LocalDate earnedOn;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private RewardRefType refType;

	private Long refId;

	@Column(length = 200)
	private String memo;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;
}
