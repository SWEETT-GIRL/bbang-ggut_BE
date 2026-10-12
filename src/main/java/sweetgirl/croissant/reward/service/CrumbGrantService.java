package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

/**
 * 부스러기를 주고 빼는 단 하나의 문. 출석·기록·상점 등 모든 보상 규칙이 여기를 지난다.
 *
 * <p>순서는 늘 같다 — <b>지갑 행을 잠그고 → (상한을 세고) → 원장에 넣고 → 잔액을 고친다.</b>
 * 지갑 잠금이 회원 한 명의 변동을 한 줄로 세우므로, 상한 판정과 지급 사이에 다른 요청이 끼어들지 못한다.
 * 같은 보상을 두 번 주지 않는 것은 원장의 {@code (member_id, idempotency_key)} 유니크가 맡는다.
 *
 * <p>부르는 쪽의 트랜잭션에 참여한다. 기록 저장이 롤백되면 그 보상도 같이 롤백돼야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CrumbGrantService {

	/** 하루의 경계는 모든 사용자에게 한국 시간 0 시다(해외 사용자도). */
	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final MemberCrumbWalletRepository walletRepository;
	private final CrumbLedgerRepository ledgerRepository;
	private final MemberRestrictionReader memberRestrictionReader;

	/** 상한이 없는 적립. 같은 멱등 키로 이미 줬으면 잔액을 건드리지 않는다. */
	public GrantResult grant(
			long memberId,
			CrumbReason reason,
			int amount,
			String idempotencyKey,
			RewardRefType refType,
			Long refId) {
		return grant(new CrumbGrant(memberId, reason, amount, idempotencyKey, refType, refId, null, null),
			LocalDate.now(ZONE));
	}

	/**
	 * 하루 상한이 있는 적립. 그날 이 이유로 받은 횟수가 상한에 닿았으면 주지 않고 {@code CAPPED} 로 답한다.
	 *
	 * @param earnedOn 한국 날짜. 요청 하나가 자정을 걸쳐도 날짜가 바뀌지 않게, 부르는 쪽이 한 번 정해 넘긴다
	 */
	public GrantResult grantWithDailyCap(
			long memberId,
			CrumbReason reason,
			int amount,
			String idempotencyKey,
			RewardRefType refType,
			Long refId,
			int dailyCap,
			LocalDate earnedOn) {
		return grant(new CrumbGrant(memberId, reason, amount, idempotencyKey, refType, refId, null, dailyCap),
			earnedOn);
	}

	/** 어드민이 사유를 적어 준다. 상한도 제재도 보지 않는다 — 사람이 판단해서 주는 것이다. */
	public GrantResult grantByAdmin(long memberId, int amount, String idempotencyKey, String memo) {
		requirePositive(amount);
		LocalDateTime now = LocalDateTime.now();
		int balance = lockWallet(memberId, now);
		return write(new CrumbGrant(memberId, CrumbReason.ADMIN_GRANT, amount, idempotencyKey, null, null, memo, null),
			balance, LocalDate.now(ZONE), now);
	}

	/**
	 * 부스러기를 쓴다. 잔액이 모자라면 {@code REWARD409_1} 로 던져 부르는 쪽 트랜잭션을 되돌린다.
	 * 같은 멱등 키로 이미 뺐으면 다시 빼지 않는다.
	 */
	public SpendResult spend(long memberId, CrumbReason reason, int amount, String idempotencyKey) {
		requirePositive(amount);
		LocalDateTime now = LocalDateTime.now();
		int balance = lockWallet(memberId, now);
		if (balance < amount) {
			throw new RewardException(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT);
		}
		return deduct(memberId, reason, amount, idempotencyKey, null, balance, now);
	}

	/** 어드민 회수. 잔액보다 크게 달라고 해도 0 까지만 뺀다. 뺄 것이 없으면 아무것도 남기지 않는다. */
	public SpendResult revokeByAdmin(long memberId, int amount, String idempotencyKey, String memo) {
		requirePositive(amount);
		LocalDateTime now = LocalDateTime.now();
		int balance = lockWallet(memberId, now);
		int revocable = Math.min(amount, balance);
		if (revocable == 0) {
			return SpendResult.alreadySpent(balance);
		}
		return deduct(memberId, CrumbReason.ADMIN_REVOKE, revocable, idempotencyKey, memo, balance, now);
	}

	/** 지금 잔액. 지갑이 아직 없으면 0 이다. */
	@Transactional(readOnly = true)
	public int balanceOf(long memberId) {
		return walletRepository.findBalance(memberId).orElse(0);
	}

	private GrantResult grant(CrumbGrant grant, LocalDate earnedOn) {
		requirePositive(grant.amount());
		LocalDateTime now = LocalDateTime.now();
		int balance = lockWallet(grant.memberId(), now);
		if (memberRestrictionReader.isWriteRestricted(grant.memberId())) {
			return GrantResult.notGranted(Outcome.RESTRICTED, balance);
		}
		if (grant.dailyCap() != null && ledgerRepository.countByMemberIdAndReasonAndEarnedOn(
			grant.memberId(), grant.reason(), earnedOn) >= grant.dailyCap()) {
			return GrantResult.notGranted(Outcome.CAPPED, balance);
		}
		return write(grant, balance, earnedOn, now);
	}

	private GrantResult write(CrumbGrant grant, int balance, LocalDate earnedOn, LocalDateTime now) {
		int inserted = ledgerRepository.insertIfAbsent(
			grant.memberId(), grant.amount(), grant.reason().name(), grant.idempotencyKey(),
			balance + grant.amount(), earnedOn,
			grant.refType() == null ? null : grant.refType().name(), grant.refId(), grant.memo(), now);
		if (inserted == 0) {
			return GrantResult.notGranted(Outcome.ALREADY_GRANTED, balance);
		}
		walletRepository.addBalance(grant.memberId(), grant.amount(), now);
		return GrantResult.granted(grant.amount(), balance);
	}

	private SpendResult deduct(
			long memberId,
			CrumbReason reason,
			int amount,
			String idempotencyKey,
			String memo,
			int balance,
			LocalDateTime now) {
		int inserted = ledgerRepository.insertIfAbsent(
			memberId, -amount, reason.name(), idempotencyKey, balance - amount, LocalDate.now(ZONE),
			null, null, memo, now);
		if (inserted == 0) {
			return SpendResult.alreadySpent(balance);
		}
		walletRepository.addBalance(memberId, -amount, now);
		return SpendResult.spent(amount, balance);
	}

	/** 지갑이 없으면 만들고, 행을 잠근 채 잔액을 읽는다. */
	private int lockWallet(long memberId, LocalDateTime now) {
		walletRepository.createIfAbsent(memberId, now);
		return walletRepository.lockBalance(memberId)
			.orElseThrow(() -> new IllegalStateException("지갑 행이 없습니다: memberId=" + memberId));
	}

	private void requirePositive(int amount) {
		if (amount <= 0) {
			throw new RewardException(RewardErrorCode.REWARD_AMOUNT_INVALID);
		}
	}

	/** 적립 한 건의 재료. 상한이 없으면 {@code dailyCap} 이 null 이다. */
	private record CrumbGrant(
			long memberId,
			CrumbReason reason,
			int amount,
			String idempotencyKey,
			RewardRefType refType,
			Long refId,
			String memo,
			Integer dailyCap) {
	}
}
