package sweetgirl.croissant.reward.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.dto.response.WorldWakeResponse;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.service.WorldCommandService;

/** 친구에게 부스러기를 쓰는 일 — 광고 대신 부스러기로 깨우기. */
@Service
@RequiredArgsConstructor
@Transactional
public class FriendCommandService {

	private final WorldCommandService worldCommandService;
	private final MemberCharacterRepository memberCharacterRepository;
	private final CrumbGrantService crumbGrantService;
	private final RewardSettings rewardSettings;
	private final MemberReader memberReader;

	/**
	 * 광고 대신 부스러기로 친구를 깨운다.
	 *
	 * <p>깨울 수 있는 조건은 광고와 같다(그 행성에 닿았고 룰렛을 돌렸을 것). 광고를 한 편이라도 본 친구는
	 * 부스러기로 못 깨운다 — 광고로 끝까지 가야 한다. 먼저 깨우고 값을 치르므로, 부스러기가 모자라면
	 * 깨운 것까지 함께 되돌려진다. 이미 깨어 있는 친구면 값을 치르기 전에 멈춘다.
	 */
	public WorldWakeResponse wakeWithCrumbs(Long memberId, String characterCode) {
		memberReader.requireLoggedIn(memberId);
		WorldPlanetResponse planet = worldCommandService.requireWakeable(memberId, characterCode);
		Optional<MemberCharacter> owned = memberCharacterRepository.findById(
			new MemberCharacterId(memberId, characterCode));
		if (owned.map(MemberCharacter::isAwake).orElse(false)) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_ALREADY_AWAKE);
		}
		if (owned.map(MemberCharacter::getAdViews).orElse(0) > 0) {
			throw new RewardException(RewardErrorCode.REWARD_WAKE_AD_STARTED);
		}

		int granted = memberCharacterRepository.grantIfAbsent(memberId, characterCode, AcquiredBy.CRUMBS.name());
		int awakened = memberCharacterRepository.awakeIfSleeping(memberId, characterCode, AcquiredBy.CRUMBS.name());
		if (granted == 0 && awakened == 0) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_ALREADY_AWAKE);
		}
		SpendResult spent = crumbGrantService.spend(
			memberId, CrumbReason.WAKE_FRIEND, rewardSettings.get(RewardSettingKey.WAKE_FRIEND),
			"wake:" + characterCode);
		return new WorldWakeResponse(characterCode, 0, planet.adsPerWake(), true,
			List.of(RewardItemResponse.spent(spent, CrumbReason.WAKE_FRIEND)));
	}
}
