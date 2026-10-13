package sweetgirl.croissant.reward.service;

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

	private void put(RewardSettingKey key, int value, LocalDateTime now) {
		rewardSettingRepository.findById(key.getKey()).ifPresentOrElse(
			setting -> setting.changeValue(value, now),
			() -> rewardSettingRepository.save(RewardSetting.of(key.getKey(), value, now)));
	}
}
