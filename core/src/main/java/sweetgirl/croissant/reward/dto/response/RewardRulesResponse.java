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
		int wakeFriend,
		/** 지난주 전국 랭킹 보상 — 1 위 · 2~3 위 · 4~10 위 */
		int rankingFirst,
		int rankingSecondThird,
		int rankingFourthTenth,
		/** 밥 룰렛을 무료 한 판 뒤 광고로 더 돌릴 수 있는 하루 횟수 */
		int feedAdDailyCap,
		/** 첫 주 두 번째 기록 보너스 — 첫 기록 7 일 안에 두 번째를 남기면 한 번 */
		int recordSecondWeek) {

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
			settings.get(RewardSettingKey.WAKE_FRIEND),
			settings.get(RewardSettingKey.RANKING_FIRST),
			settings.get(RewardSettingKey.RANKING_SECOND_THIRD),
			settings.get(RewardSettingKey.RANKING_FOURTH_TENTH),
			settings.get(RewardSettingKey.FEED_AD_DAILY_CAP),
			settings.get(RewardSettingKey.RECORD_SECOND_WEEK));
	}
}
