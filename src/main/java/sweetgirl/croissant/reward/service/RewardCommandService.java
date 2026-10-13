package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@Service
@RequiredArgsConstructor
@Transactional
public class RewardCommandService {

	static final String CARRY_OVER_KEY = "carry_over";
	static final String WELCOME_KEY = "welcome";

	private final CrumbGrantService crumbGrantService;
	private final RewardDailyCommandService rewardDailyCommandService;
	private final RewardPendingService rewardPendingService;
	private final RewardSettings rewardSettings;
	private final RewardMemberPolicy rewardMemberPolicy;
	private final MemberCrumbWalletRepository walletRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final MemberReader memberReader;

	/**
	 * 1.x 에서 넘어온 회원에게 이관 선물을 한 번 준다. 요약 조회가 읽기 전에 부른다.
	 *
	 * <p>방금 줬을 때만 값을 돌려준다 — 앱이 그 한 번에 안내 화면을 띄운다. 멱등 키가 하나라 요약이 동시에 둘 들어와도
	 * 한쪽만 준다. 제재 중이라 못 줬으면 준 것으로 남기지 않는다 — 제재가 풀린 뒤의 조회에서 받는다.
	 * 줄 때 1.x 의 연속 기록 일수를 출석판 자리로도 옮긴다.
	 */
	public Optional<CarryOver> carryOverIfNeeded(Long memberId) {
		Member member = memberReader.getMember(memberId);
		if (!rewardMemberPolicy.isCarryOverTarget(member)
			|| walletRepository.findCarriedOverAt(memberId).isPresent()) {
			return Optional.empty();
		}

		int recordCount = (int)breadRecordRepository.countByMemberIdAndRecordTypeAndDeletedAtIsNull(
			memberId, RecordType.NORMAL);
		int amount = Math.min(
			rewardSettings.get(RewardSettingKey.CARRY_OVER_MAX),
			rewardSettings.get(RewardSettingKey.CARRY_OVER_BASE)
				+ rewardSettings.get(RewardSettingKey.CARRY_OVER_PER_RECORD) * recordCount);
		if (amount <= 0) {
			walletRepository.createIfAbsent(memberId, LocalDateTime.now());
			walletRepository.markCarriedOver(memberId, LocalDateTime.now());
			return Optional.empty();
		}

		GrantResult result = crumbGrantService.grant(
			memberId, CrumbReason.CARRY_OVER, amount, CARRY_OVER_KEY, null, null);
		if (result.outcome() == Outcome.RESTRICTED) {
			return Optional.empty();
		}
		walletRepository.markCarriedOver(memberId, LocalDateTime.now());
		if (!result.isGranted()) {
			return Optional.empty();
		}
		rewardDailyCommandService.seedAttendanceFromRecordStreak(memberId, LocalDate.now(CrumbGrantService.ZONE));
		return Optional.of(new CarryOver(true, amount, recordCount));
	}

	/**
	 * 환영 선물 — 2.0 에서 새로 온 회원이 처음 홈에 선 순간 한 번.
	 * 기존 사용자(이관 대상)와 재가입한 회원은 받지 않는다.
	 */
	public RewardClaimResponse claimWelcome(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		if (!rewardMemberPolicy.isWelcomeTarget(memberReader.getMember(memberId))) {
			return RewardClaimResponse.done();
		}
		GrantResult result = crumbGrantService.grant(
			memberId, CrumbReason.WELCOME, rewardSettings.get(RewardSettingKey.WELCOME), WELCOME_KEY, null, null);
		if (result.isGranted()) {
			return new RewardClaimResponse(false,
				List.of(RewardItemResponse.crumbs(result, CrumbReason.WELCOME)));
		}
		return new RewardClaimResponse(result.outcome() == Outcome.ALREADY_GRANTED, List.of());
	}

	/** 앱이 연출을 끝낸 보상을 지운다. */
	public void acknowledgePending(Long memberId, List<Long> ids) {
		memberReader.requireLoggedIn(memberId);
		rewardPendingService.acknowledge(memberId, ids);
	}
}
