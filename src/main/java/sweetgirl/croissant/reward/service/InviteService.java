package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.InviteAcceptResponse;
import sweetgirl.croissant.reward.dto.response.InviteResponse;
import sweetgirl.croissant.reward.dto.response.InviteResponse.Friend;
import sweetgirl.croissant.reward.dto.response.InviteResponse.InvitedBy;
import sweetgirl.croissant.reward.dto.response.InviteResponse.Waiting;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.entity.MemberInvite;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.InviteFriendStatus;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.MemberInviteRepository;

/**
 * 친구 초대. 초대받은 사람이 코드를 넣고 30 일 안에 첫 빵을 기록하면 둘 다 부스러기를 받는다.
 *
 * <p>코드는 가입한 지 7 일 안, 아직 기록이 없을 때 한 번만 넣을 수 있다. 탈퇴 후 재가입한 회원은 넣을 수 없다.
 * 서로의 코드를 넣는 것도 막는다 — 둘이 짜고 보상을 나눠 갖는 길이고, 두 지갑을 반대 순서로 잠그는 길이기도 하다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InviteService {

	/** 가입한 뒤 이 기간 안에만 코드를 넣을 수 있다. */
	static final int ENTER_CODE_DAYS = 7;
	/** 코드가 겹쳤을 때 다시 만들어 보는 횟수. */
	private static final int GENERATE_ATTEMPTS = 5;

	private final MemberInviteRepository memberInviteRepository;
	private final MemberRepository memberRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final InviteCodeGenerator inviteCodeGenerator;
	private final CrumbGrantService crumbGrantService;
	private final RewardPendingService rewardPendingService;
	private final RewardSettings rewardSettings;
	private final MemberReader memberReader;

	/** 내 초대 현황. 코드가 아직 없으면 여기서 만든다. */
	public InviteResponse getMyInvite(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		Member member = memberReader.getMember(memberId);
		LocalDateTime now = LocalDateTime.now();
		int expireDays = rewardSettings.get(RewardSettingKey.INVITE_EXPIRE_DAYS);

		List<MemberInvite> invites = memberInviteRepository.findAllByInviterIdOrderByIdAsc(memberId);
		// 회원 행이 없는 친구는 waiting · friends 둘 다에서 뺀다(탈퇴는 행이 남아 "탈퇴한 회원"으로 나온다)
		Map<Long, Member> inviteeById = memberRepository
			.findAllById(invites.stream().map(MemberInvite::getInviteeId).toList()).stream()
			.collect(Collectors.toMap(Member::getId, Function.identity()));
		List<MemberInvite> shown = invites.stream()
			.filter(invite -> inviteeById.containsKey(invite.getInviteeId()))
			.toList();

		MemberInvite accepted = memberInviteRepository.findByInviteeId(memberId).orElse(null);
		return new InviteResponse(
			ensureCode(member),
			invites.size(),
			shown.stream()
				.filter(invite -> statusOf(invite, now, expireDays) == InviteFriendStatus.WAITING)
				.map(invite -> new Waiting(
					inviteeById.get(invite.getInviteeId()).getNickname(), expiresOn(invite, expireDays)))
				.toList(),
			(int)invites.stream()
				.filter(invite -> statusOf(invite, now, expireDays) == InviteFriendStatus.REWARDED)
				.count(),
			rewardSettings.get(RewardSettingKey.INVITE_INVITER_CAP),
			rewardSettings.get(RewardSettingKey.INVITE),
			accepted == null ? null : memberRepository.findById(accepted.getInviterId())
				.map(inviter -> new InvitedBy(inviter.getNickname()))
				.orElse(null),
			accepted == null && canEnterCode(member, now),
			shown.stream()
				.sorted(Comparator.comparing(MemberInvite::getCreatedAt).thenComparing(MemberInvite::getId)
					.reversed())
				.map(invite -> toFriend(invite, inviteeById.get(invite.getInviteeId()), now, expireDays))
				.toList());
	}

	/** 성사됐으면 REWARDED, 기한이 지났으면 EXPIRED, 아니면 WAITING. waiting · rewardedCount · friends 가 모두 이것으로 가른다. */
	static InviteFriendStatus statusOf(MemberInvite invite, LocalDateTime now, int expireDays) {
		if (invite.isRewarded()) {
			return InviteFriendStatus.REWARDED;
		}
		return invite.isExpiredAt(now, expireDays) ? InviteFriendStatus.EXPIRED : InviteFriendStatus.WAITING;
	}

	private static LocalDate expiresOn(MemberInvite invite, int expireDays) {
		return invite.getCreatedAt().plusDays(expireDays).toLocalDate();
	}

	private static Friend toFriend(MemberInvite invite, Member invitee, LocalDateTime now, int expireDays) {
		InviteFriendStatus status = statusOf(invite, now, expireDays);
		return new Friend(
			invitee.getNickname(),
			status,
			invite.getCreatedAt(),
			status == InviteFriendStatus.REWARDED ? null : expiresOn(invite, expireDays),
			invite.getRewardedAt());
	}

	/** 초대 코드를 넣는다. */
	public InviteAcceptResponse accept(Long memberId, String code) {
		memberReader.requireLoggedIn(memberId);
		Member member = memberReader.getMember(memberId);
		if (member.isRejoined()) {
			throw new RewardException(RewardErrorCode.REWARD_INVITE_REJOINED);
		}
		if (memberInviteRepository.existsByInviteeId(memberId)) {
			throw new RewardException(RewardErrorCode.REWARD_INVITE_ALREADY_ACCEPTED);
		}
		if (!canEnterCode(member, LocalDateTime.now())) {
			throw new RewardException(RewardErrorCode.REWARD_INVITE_TOO_LATE);
		}
		Member inviter = memberRepository
			.findByInviteCodeAndDeletedAtIsNull(code.strip().toUpperCase(Locale.ROOT))
			.filter(found -> !found.isGuest())
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_INVITE_CODE_NOT_FOUND));
		if (inviter.getId().equals(memberId)) {
			throw new RewardException(RewardErrorCode.REWARD_INVITE_OWN_CODE);
		}
		if (memberInviteRepository.existsByInviterIdAndInviteeId(memberId, inviter.getId())) {
			throw new RewardException(RewardErrorCode.REWARD_INVITE_MUTUAL);
		}
		if (memberInviteRepository.insertIfAbsent(inviter.getId(), memberId, LocalDateTime.now()) == 0) {
			throw new RewardException(RewardErrorCode.REWARD_INVITE_ALREADY_ACCEPTED);
		}
		return new InviteAcceptResponse(inviter.getNickname());
	}

	/**
	 * 빵을 기록했을 때 부른다. 이 회원이 초대받은 사람이고 아직 성사되지 않은 초대가 있으면 지금 성사시킨다.
	 *
	 * <p>코드는 기록이 없을 때만 넣을 수 있으므로, 코드를 넣은 뒤의 첫 기록이 곧 첫 빵이다.
	 * 초대받은 사람의 보상은 이 기록의 응답에 실리고, 초대한 사람의 보상은 그 사람의 다음 요약 조회에 연출 대기로 담긴다.
	 *
	 * @return 초대받은 사람(이 회원)이 받은 보상
	 */
	public List<RewardItemResponse> rewardFirstRecord(Member invitee) {
		if (invitee.isGuest()) {
			return List.of();
		}
		MemberInvite invite = memberInviteRepository.findByInviteeId(invitee.getId()).orElse(null);
		LocalDateTime now = LocalDateTime.now();
		if (invite == null || invite.isRewarded()
			|| invite.isExpiredAt(now, rewardSettings.get(RewardSettingKey.INVITE_EXPIRE_DAYS))
			|| memberInviteRepository.markRewarded(invite.getId(), now) == 0) {
			return List.of();
		}

		int reward = rewardSettings.get(RewardSettingKey.INVITE);
		GrantResult inviteeGrant = crumbGrantService.grant(
			invitee.getId(), CrumbReason.INVITE_INVITEE, reward, "invite:" + invite.getId() + ":invitee",
			RewardRefType.INVITE, invite.getId());
		rewardInviter(invite, reward);
		return inviteeGrant.isGranted()
			? List.of(RewardItemResponse.crumbs(inviteeGrant, CrumbReason.INVITE_INVITEE))
			: List.of();
	}

	/** 초대한 사람은 성사 10 명까지만 받는다. 탈퇴했으면 받지 않는다(친구만 받는다). */
	private void rewardInviter(MemberInvite invite, int reward) {
		Long inviterId = invite.getInviterId();
		boolean active = memberRepository.findByIdAndDeletedAtIsNull(inviterId)
			.map(inviter -> !inviter.isGuest())
			.orElse(false);
		if (!active) {
			return;
		}
		// 성사 수를 세는 일과 지급이 한 줄에 서야 한다 — 친구 둘이 동시에 첫 기록을 써도 10 명을 넘지 않게
		crumbGrantService.serialize(inviterId);
		if (memberInviteRepository.countByInviterIdAndRewardedAtIsNotNull(inviterId) > rewardSettings
			.get(RewardSettingKey.INVITE_INVITER_CAP)) {
			return;
		}
		String key = "invite:" + invite.getId() + ":inviter";
		GrantResult inviterGrant = crumbGrantService.grant(
			inviterId, CrumbReason.INVITE_INVITER, reward, key, RewardRefType.INVITE, invite.getId());
		if (inviterGrant.isGranted()) {
			rewardPendingService.add(inviterId, key,
				RewardItemResponse.crumbs(inviterGrant, CrumbReason.INVITE_INVITER));
		}
	}

	private boolean canEnterCode(Member member, LocalDateTime now) {
		LocalDateTime registeredAt = member.getRegisteredAt() != null
			? member.getRegisteredAt()
			: member.getCreatedAt();
		return !member.isRejoined()
			&& !registeredAt.plusDays(ENTER_CODE_DAYS).isBefore(now)
			&& breadRecordRepository.countByMemberIdAndRecordTypeAndDeletedAtIsNull(
				member.getId(), RecordType.NORMAL) == 0;
	}

	/** 코드가 없으면 만들어 붙인다. 겹치면 다시 만든다. */
	private String ensureCode(Member member) {
		if (member.getInviteCode() != null) {
			return member.getInviteCode();
		}
		for (int attempt = 0; attempt < GENERATE_ATTEMPTS; attempt++) {
			String code = inviteCodeGenerator.generate();
			if (memberRepository.existsByInviteCode(code)) {
				continue;
			}
			memberRepository.assignInviteCodeIfAbsent(member.getId(), code);
			// 나란히 들어온 다른 요청이 먼저 붙였을 수 있다 — 붙어 있는 값을 읽어 돌려준다
			return memberRepository.findInviteCode(member.getId()).orElse(code);
		}
		throw new RewardException(RewardErrorCode.REWARD_INVITE_CODE_UNAVAILABLE);
	}
}
