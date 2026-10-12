package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.world.service.WorldLevel;
import sweetgirl.croissant.world.service.WorldLevelReader;

/**
 * 빵 기록에 붙는 보상. 기록 저장과 같은 트랜잭션에서 돈다 — 기록이 롤백되면 보상도 같이 사라진다.
 *
 * <p>돌려주는 목록의 순서가 곧 앱의 연출 순서다: 빵 칸 → 기록 부스러기 → 사진 부스러기 → 레벨업.
 * 게스트는 보상이 없다(기록은 남는다).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RecordRewardService {

	/** 기록한 뒤 이 기간 안에 사진을 붙이면 사진 보너스를 준다. */
	static final int PHOTO_BONUS_DAYS = 7;

	private final CrumbGrantService crumbGrantService;
	private final RewardPendingService rewardPendingService;
	private final RewardSettings rewardSettings;
	private final CrumbLedgerRepository ledgerRepository;
	private final WorldLevelReader worldLevelReader;
	private final InviteService inviteService;

	/**
	 * 새 기록의 보상.
	 *
	 * @param photoCount   이 기록의 사진 수
	 * @param heightBefore 저장 전 빵탑의 최고 높이
	 * @param heightAfter  저장 후 빵탑의 최고 높이. 지우고 다시 쓴 기록이면 오르지 않는다
	 */
	public List<RewardItemResponse> rewardCreated(
			Member member,
			Long recordId,
			int photoCount,
			int heightBefore,
			int heightAfter) {
		return rewardCreated(member, recordId, photoCount, heightBefore, heightAfter, "record:" + recordId);
	}

	/**
	 * 빵투어에서 한 번 탭으로 만든 가벼운 기록의 보상. 보통 기록과 같은 하루 상한에 세되, 멱등 키가 기록이 아니라
	 * (빵투어, 가게)다 — 가벼운 기록을 지우고 다시 눌러 기록이 새로 생겨도 보상은 처음 한 번뿐이다.
	 */
	public List<RewardItemResponse> rewardTourRecordCreated(
			Member member,
			Long recordId,
			Long courseId,
			Long storeId,
			int heightBefore,
			int heightAfter) {
		return rewardCreated(member, recordId, 0, heightBefore, heightAfter,
			"tour_record:" + courseId + ":" + storeId);
	}

	private List<RewardItemResponse> rewardCreated(
			Member member,
			Long recordId,
			int photoCount,
			int heightBefore,
			int heightAfter,
			String recordRewardKey) {
		if (member.isGuest()) {
			return List.of();
		}
		Long memberId = member.getId();
		List<RewardItemResponse> rewards = new ArrayList<>();
		if (heightAfter > heightBefore) {
			rewards.add(RewardItemResponse.bread(heightBefore, heightAfter));
		}

		GrantResult record = crumbGrantService.grantWithDailyCap(
			memberId, CrumbReason.RECORD, rewardSettings.get(RewardSettingKey.RECORD), recordRewardKey,
			RewardRefType.RECORD, recordId, rewardSettings.get(RewardSettingKey.RECORD_DAILY_CAP),
			LocalDate.now(CrumbGrantService.ZONE));
		if (record.isGranted() || record.isCapped()) {
			rewards.add(RewardItemResponse.crumbs(record, CrumbReason.RECORD));
		}
		// 사진 보너스는 기록 보상을 받은 기록에만 붙는다 — 상한에 걸린 기록은 사진이 있어도 없다
		if (record.isGranted() && photoCount > 0) {
			grantPhotoBonus(memberId, recordId, rewards);
		}

		// 초대받은 회원의 첫 빵이면 초대가 성사된다
		rewards.addAll(inviteService.rewardFirstRecord(member));

		if (heightAfter > heightBefore) {
			addLevelUp(memberId, heightBefore, heightAfter, rewards);
		}
		return rewards;
	}

	/**
	 * 기록에 나중에 사진을 붙였을 때의 보너스. 기록 보상을 받은 기록이고, 기록한 지 7 일 안일 때 한 번 준다.
	 * 받은 뒤 사진을 지웠다 다시 붙여도 또 주지 않는다(멱등 키가 기록 하나에 하나다).
	 *
	 * @param recordedAt 기록을 저장한 때(서버 시각). 앱이 보낸 먹은 날이 아니다
	 */
	public List<RewardItemResponse> rewardPhotosAttached(
			Member member,
			Long recordId,
			LocalDateTime recordedAt,
			int photoCount) {
		if (member.isGuest() || photoCount == 0
			|| recordedAt.plusDays(PHOTO_BONUS_DAYS).isBefore(LocalDateTime.now())
			|| !ledgerRepository.existsByMemberIdAndReasonAndRefTypeAndRefId(
				member.getId(), CrumbReason.RECORD, RewardRefType.RECORD, recordId)) {
			return List.of();
		}
		List<RewardItemResponse> rewards = new ArrayList<>();
		grantPhotoBonus(member.getId(), recordId, rewards);
		return rewards;
	}

	private void grantPhotoBonus(Long memberId, Long recordId, List<RewardItemResponse> rewards) {
		GrantResult photo = crumbGrantService.grant(
			memberId, CrumbReason.RECORD_PHOTO, rewardSettings.get(RewardSettingKey.RECORD_PHOTO),
			"record_photo:" + recordId, RewardRefType.RECORD, recordId);
		if (photo.isGranted()) {
			rewards.add(RewardItemResponse.crumbs(photo, CrumbReason.RECORD_PHOTO));
		}
	}

	/**
	 * 레벨이 올랐으면 싣고, 앱이 연출을 못 보고 꺼졌을 때를 위해 따로도 남긴다.
	 * 남긴 것은 다음 요약 조회의 pendingRewards 로 다시 내려가고, 앱이 연출을 끝내고 ack 하면 지워진다.
	 */
	private void addLevelUp(Long memberId, int heightBefore, int heightAfter, List<RewardItemResponse> rewards) {
		WorldLevel before = worldLevelReader.levelAt(memberId, heightBefore);
		WorldLevel after = worldLevelReader.levelAt(memberId, heightAfter);
		if (after.level() <= before.level()) {
			return;
		}
		RewardItemResponse levelUp = RewardItemResponse.levelUp(
			before.level(), after.level(), after.planet(), after.stage());
		rewards.add(levelUp);
		rewardPendingService.add(memberId, "level_up:" + after.level(), levelUp);
	}
}
