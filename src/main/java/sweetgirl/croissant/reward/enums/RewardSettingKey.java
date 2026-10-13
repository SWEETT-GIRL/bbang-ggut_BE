package sweetgirl.croissant.reward.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 어드민이 바꿀 수 있는 보상 수치와 출시 스위치({@code reward_settings.setting_key}).
 *
 * <p>기본값은 DB 에 행이 없을 때만 쓴다 — 시드가 빠진 환경에서도 보상이 0 으로 나가지 않게 하는 안전망이다.
 * 앱의 {@code src/constants/rewards.ts} 와 같은 값이어야 한다.
 */
@Getter
@RequiredArgsConstructor
public enum RewardSettingKey {

	WELCOME("welcome", 25),
	RECORD("record", 10),
	RECORD_PHOTO("record.photo", 5),
	RECORD_DAILY_CAP("record.daily-cap", 3),
	ATTENDANCE_DAY1("attendance.day1", 5),
	ATTENDANCE_DAY2("attendance.day2", 5),
	ATTENDANCE_DAY3("attendance.day3", 10),
	ATTENDANCE_DAY4("attendance.day4", 5),
	ATTENDANCE_DAY5("attendance.day5", 10),
	ATTENDANCE_DAY6("attendance.day6", 10),
	ATTENDANCE_DAY7("attendance.day7", 30),
	FEED_WEDGE0("feed.wedge0", 10),
	FEED_WEDGE1("feed.wedge1", 5),
	FEED_WEDGE2("feed.wedge2", 3),
	FEED_WEDGE3("feed.wedge3", 5),
	FEED_WEDGE4("feed.wedge4", 10),
	FEED_WEDGE5("feed.wedge5", 50),
	FEED_WEIGHT_3("feed.weight.3", 45),
	FEED_WEIGHT_5("feed.weight.5", 35),
	FEED_WEIGHT_10("feed.weight.10", 17),
	FEED_WEIGHT_50("feed.weight.50", 3),
	SAVE_STORE("save-store", 2),
	SAVE_STORE_DAILY_CAP("save-store.daily-cap", 3),
	LETTER("letter", 10),
	LETTER_MAX_UNREAD("letter.max-unread", 4),
	AD("ad", 20),
	AD_DAILY_CAP("ad.daily-cap", 2),
	INVITE("invite", 100),
	INVITE_INVITER_CAP("invite.inviter-cap", 10),
	INVITE_EXPIRE_DAYS("invite.expire-days", 30),
	TOUR_VISIT("tour.visit", 3),
	TOUR_VISIT_DAILY_CAP("tour.visit.daily-cap", 8),
	TOUR_COMPLETE("tour.complete", 30),
	TOUR_PHOTO_COMPLETE("tour.photo-complete", 30),
	RANKING_FIRST("ranking.first", 100),
	RANKING_SECOND_THIRD("ranking.second-third", 50),
	RANKING_FOURTH_TENTH("ranking.fourth-tenth", 20),
	WAKE_FRIEND("wake-friend", 600),
	CARRY_OVER_BASE("carry-over.base", 50),
	CARRY_OVER_PER_RECORD("carry-over.per-record", 5),
	CARRY_OVER_MAX("carry-over.max", 300),
	/** 이 날짜(yyyyMMdd, 한국 날짜) 전에 가입했으면 기존 사용자다. 0 이면 아직 안 정했다 — 설정 파일의 값을 쓴다 */
	CARRY_OVER_BEFORE_DATE("carry-over.before-date", 0),
	/** 위 날짜의 시각(HHmm, 한국 시간). 1430 이면 오후 2 시 30 분이다 */
	CARRY_OVER_BEFORE_TIME("carry-over.before-time", 0);

	private final String key;
	private final int defaultValue;

	public static Optional<RewardSettingKey> findByKey(String key) {
		return Arrays.stream(values()).filter(setting -> setting.key.equals(key)).findFirst();
	}
}
