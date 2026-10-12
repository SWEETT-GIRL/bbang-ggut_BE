package sweetgirl.croissant.reward.dto.response;

import java.util.List;

import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.service.RewardSettings;

/** 앱이 화면에 적는 보상 수치. 어드민이 바꾸면 앱 배포 없이 따라온다. */
public record RewardRulesResponse(
		int welcome,
		int record,
		int recordPhoto,
		int recordDailyCap,
		List<Integer> attendance,
		int saveStore,
		int saveStoreDailyCap,
		int letter,
		int ad,
		int adDailyCap,
		int invite,
		int inviteCap,
		int tourVisit,
		int tourVisitCap,
		int tourComplete,
		int tourPhotoComplete,
		int wakeFriend) {

	public static RewardRulesResponse from(RewardSettings settings) {
		return new RewardRulesResponse(
			settings.get(RewardSettingKey.WELCOME),
			settings.get(RewardSettingKey.RECORD),
			settings.get(RewardSettingKey.RECORD_PHOTO),
			settings.get(RewardSettingKey.RECORD_DAILY_CAP),
			settings.attendance(),
			settings.get(RewardSettingKey.SAVE_STORE),
			settings.get(RewardSettingKey.SAVE_STORE_DAILY_CAP),
			settings.get(RewardSettingKey.LETTER),
			settings.get(RewardSettingKey.AD),
			settings.get(RewardSettingKey.AD_DAILY_CAP),
			settings.get(RewardSettingKey.INVITE),
			settings.get(RewardSettingKey.INVITE_INVITER_CAP),
			settings.get(RewardSettingKey.TOUR_VISIT),
			settings.get(RewardSettingKey.TOUR_VISIT_DAILY_CAP),
			settings.get(RewardSettingKey.TOUR_COMPLETE),
			settings.get(RewardSettingKey.TOUR_PHOTO_COMPLETE),
			settings.get(RewardSettingKey.WAKE_FRIEND));
	}
}
