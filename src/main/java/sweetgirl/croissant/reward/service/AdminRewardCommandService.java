package sweetgirl.croissant.reward.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.request.AdminBbangdaengLetterRequest;
import sweetgirl.croissant.reward.dto.request.AdminCrumbAdjustRequest;
import sweetgirl.croissant.reward.dto.request.AdminRewardSettingsUpdateRequest;
import sweetgirl.croissant.reward.dto.response.AdminBbangdaengLetterResponse;
import sweetgirl.croissant.reward.dto.response.AdminCrumbAdjustResponse;
import sweetgirl.croissant.reward.entity.BbangdaengLetter;
import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.BbangdaengLetterRepository;
import sweetgirl.croissant.reward.repository.RewardSettingRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminRewardCommandService {

	private final CrumbGrantService crumbGrantService;
	private final RewardSettingRepository rewardSettingRepository;
	private final RewardSettings rewardSettings;
	private final MemberReader memberReader;
	private final BbangdaengLetterRepository bbangdaengLetterRepository;

	/** 게스트에게는 주지 않는다 — 게스트는 지갑이 없고, 로그인하면 사라질 행을 만들지 않는다. */
	public AdminCrumbAdjustResponse grant(Long memberId, AdminCrumbAdjustRequest request) {
		memberReader.requireLoggedIn(memberId);
		GrantResult result = crumbGrantService.grantByAdmin(
			memberId, request.amount(), idempotencyKey(request), request.memo());
		return new AdminCrumbAdjustResponse(result.isGranted(), result.amount(), result.to());
	}

	public AdminCrumbAdjustResponse revoke(Long memberId, AdminCrumbAdjustRequest request) {
		memberReader.requireLoggedIn(memberId);
		SpendResult result = crumbGrantService.revokeByAdmin(
			memberId, request.amount(), idempotencyKey(request), request.memo());
		return new AdminCrumbAdjustResponse(result.spent(), result.amount(), result.to());
	}

	/** 보낸 키만 바꾼다. 모르는 키가 하나라도 있으면 아무것도 바꾸지 않는다. */
	public void updateSettings(AdminRewardSettingsUpdateRequest request) {
		List<AdminRewardSettingsUpdateRequest.Entry> entries = request.settings();
		entries.forEach(entry -> RewardSettingKey.findByKey(entry.key())
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_SETTING_NOT_FOUND)));

		LocalDateTime now = LocalDateTime.now();
		for (AdminRewardSettingsUpdateRequest.Entry entry : entries) {
			rewardSettingRepository.findById(entry.key()).ifPresentOrElse(
				setting -> setting.changeValue(entry.value(), now),
				() -> rewardSettingRepository.save(RewardSetting.of(entry.key(), entry.value(), now)));
		}
		rewardSettings.evict();
	}

	/** 편지 글을 묶음 끝에 더한다. */
	public AdminBbangdaengLetterResponse addLetter(AdminBbangdaengLetterRequest request) {
		BbangdaengLetter letter = bbangdaengLetterRepository.save(BbangdaengLetter.write(
			bbangdaengLetterRepository.findMaxSeq() + 1, request.body().strip(), LocalDateTime.now()));
		return AdminBbangdaengLetterResponse.from(letter);
	}

	/** 글을 고치거나 끈다. 끈 글은 새로 배달하지 않을 뿐, 이미 받은 편지에서는 그대로 보인다. */
	public AdminBbangdaengLetterResponse reviseLetter(Long letterId, AdminBbangdaengLetterRequest request) {
		BbangdaengLetter letter = bbangdaengLetterRepository.findById(letterId)
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_LETTER_NOT_FOUND));
		letter.revise(request.body().strip(), !Boolean.FALSE.equals(request.active()));
		return AdminBbangdaengLetterResponse.from(letter);
	}

	@Transactional(readOnly = true)
	public List<AdminBbangdaengLetterResponse> getLetters() {
		return bbangdaengLetterRepository.findAllByOrderBySeqAsc().stream()
			.map(AdminBbangdaengLetterResponse::from)
			.toList();
	}

	private String idempotencyKey(AdminCrumbAdjustRequest request) {
		UUID requestId = request.requestId() == null ? UUID.randomUUID() : request.requestId();
		return "admin:" + requestId;
	}
}
