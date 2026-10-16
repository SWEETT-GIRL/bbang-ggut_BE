package sweetgirl.croissant.reward.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.RewardSettingRepository;

/**
 * 리워드를 여는 순간 — 2.0 앱이 풀리는 날 사람이 따로 누를 것을 대신 넣는다.
 *
 * <p>출시 시각(기존 회원을 가르는 선)과 랭킹 주간 보상 스위치는 앱이 스토어에 풀리는 순간에 맞춰야 한다.
 * 그 순간을 서버가 알 수 있는 신호는 강제 업데이트뿐이라, 거기에 묶는다.
 * 넣은 값은 어드민 설정에 그대로 보이고, 어드민이 고칠 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardLaunchService {

	private final RewardSettingRepository rewardSettingRepository;
	private final RewardSettings rewardSettings;

	/**
	 * 아직 안 열었으면 지금을 출시 시각으로 적고 랭킹 주간 보상을 켠다.
	 *
	 * <p>출시 시각이 이미 있으면(어드민이 넣었거나 앞서 열었으면) 아무것도 하지 않는다 —
	 * 두 번째 플랫폼의 강제 업데이트가 선을 뒤로 밀면 그 사이 가입자가 기존 회원으로 바뀐다.
	 *
	 * @param now 지금(한국 시간)
	 * @return 이번 호출로 열었는가
	 */
	@Transactional
	public boolean openIfNotYet(LocalDateTime now) {
		// 캐시가 아니라 표를 본다 — 다른 인스턴스가 방금 열었을 수 있다
		boolean opened = rewardSettingRepository.findById(RewardSettingKey.CARRY_OVER_BEFORE_DATE.getKey())
			.map(setting -> setting.getIntValue() != 0)
			.orElse(false);
		if (opened) {
			return false;
		}
		put(RewardSettingKey.CARRY_OVER_BEFORE_DATE,
			now.getYear() * 10000 + now.getMonthValue() * 100 + now.getDayOfMonth(), now);
		put(RewardSettingKey.CARRY_OVER_BEFORE_TIME, now.getHour() * 100 + now.getMinute(), now);
		put(RewardSettingKey.RANKING_ISSUE, 1, now);
		rewardSettings.evict();
		log.info("[리워드] 출시 시각을 {} 로 적고 랭킹 주간 보상을 켰다", now);
		return true;
	}

	/**
	 * 이 푸시를 보낼 때가 됐는가. 어드민이 스위치를 켰거나, 출시 시각에서 정해 둔 날수가 지났으면 보낸다.
	 *
	 * <p>출시 직후에는 2.0 을 아직 안 깐 회원이 많아 눌러도 열 화면이 없다. 그래서 며칠 뒤에 켜지게 한다.
	 * 저절로 켜지는 것을 막으려면 {@code push.auto-after-days} 를 0 으로 둔다.
	 *
	 * @param pushSwitch {@code push.letter} · {@code push.attendance} · {@code push.course-tomorrow}
	 * @param today      오늘(한국 날짜)
	 */
	public boolean isPushDue(RewardSettingKey pushSwitch, LocalDate today) {
		if (rewardSettings.isOn(pushSwitch)) {
			return true;
		}
		int afterDays = rewardSettings.get(RewardSettingKey.PUSH_AUTO_AFTER_DAYS);
		if (afterDays <= 0) {
			return false;
		}
		try {
			return RewardMemberPolicy
				.cutoffOf(rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_DATE),
					rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_TIME))
				.map(launchedAt -> !today.isBefore(launchedAt.toLocalDate().plusDays(afterDays)))
				.orElse(false);
		} catch (DateTimeException exception) {
			return false;
		}
	}

	private void put(RewardSettingKey key, int value, LocalDateTime now) {
		rewardSettingRepository.findById(key.getKey()).ifPresentOrElse(
			setting -> setting.changeValue(value, now),
			() -> rewardSettingRepository.save(RewardSetting.of(key.getKey(), value, now)));
	}
}
