package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.SaveStoreReward;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;

/** 작은 행동에 붙는 보상 — 빵집 담기와 광고 보기. 부르는 쪽의 트랜잭션에서 돈다. */
@Service
@RequiredArgsConstructor
@Transactional
public class EarnRewardService {

	private final CrumbGrantService crumbGrantService;
	private final RewardSettings rewardSettings;
	private final MemberRepository memberRepository;

	/**
	 * 빵집을 새로 담았을 때의 보상. 하루 3 번까지고, 같은 가게는 평생 한 번이다 —
	 * 멱등 키가 가게라서 담았다 풀었다 다시 담아도 또 주지 않는다.
	 */
	public List<RewardItemResponse> rewardStoreSaved(Long memberId, Long storeId) {
		GrantResult result = crumbGrantService.grantWithDailyCap(
			memberId, CrumbReason.SAVE_STORE, rewardSettings.get(RewardSettingKey.SAVE_STORE), "save:" + storeId,
			RewardRefType.STORE, storeId, rewardSettings.get(RewardSettingKey.SAVE_STORE_DAILY_CAP),
			LocalDate.now(CrumbGrantService.ZONE));
		return result.isGranted() || result.isCapped()
			? List.of(RewardItemResponse.crumbs(result, CrumbReason.SAVE_STORE))
			: List.of();
	}

	/**
	 * 광고를 끝까지 봤을 때의 보상. 하루 2 번까지다. 구글의 서버 검증 콜백에서만 부른다 — 앱의 요청으로는 주지 않는다.
	 *
	 * @return 부스러기를 줬는가. 상한을 넘었거나 같은 광고를 이미 처리했으면 false
	 */
	public boolean rewardAdWatched(Long memberId, String transactionId) {
		return crumbGrantService.grantWithDailyCap(
			memberId, CrumbReason.AD, rewardSettings.get(RewardSettingKey.AD), "ad:" + transactionId,
			null, null, rewardSettings.get(RewardSettingKey.AD_DAILY_CAP),
			LocalDate.now(CrumbGrantService.ZONE)).isGranted();
	}

	/** 오늘 광고로 더 받을 수 있는 횟수. */
	@Transactional(readOnly = true)
	public int adsLeftToday(Long memberId) {
		long watched = crumbGrantService.countToday(memberId, CrumbReason.AD);
		return (int)Math.max(0, rewardSettings.get(RewardSettingKey.AD_DAILY_CAP) - watched);
	}

	/** 오늘 빵집 담기로 받은 횟수와 더 받을 수 있는 횟수. 상한은 {@link #rewardStoreSaved} 와 같은 설정값이다. */
	@Transactional(readOnly = true)
	public SaveStoreReward saveStoreRewardOf(Long memberId) {
		int rewarded = (int)crumbGrantService.countToday(memberId, CrumbReason.SAVE_STORE);
		return new SaveStoreReward(rewarded,
			Math.max(0, rewardSettings.get(RewardSettingKey.SAVE_STORE_DAILY_CAP) - rewarded));
	}

	/** 부스러기를 받을 수 있는 회원인가 — 탈퇴하지 않은, 게스트가 아닌 회원. */
	@Transactional(readOnly = true)
	public boolean canEarn(Long memberId) {
		return memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.map(member -> !member.isGuest())
			.orElse(false);
	}
}
