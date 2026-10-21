package sweetgirl.croissant.reward.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.reward.enums.RewardSettingKey;

/**
 * 이 회원이 1.x 에서 넘어온 사람인가, 2.0 에서 새로 온 사람인가.
 *
 * <p>가르는 기준은 <b>소셜로 가입한 시각</b>이 2.0 출시 시각보다 앞인가다. 게스트로만 쓰다 2.0 에서 처음 로그인한 사람은
 * 새 회원이다 — 그래서 {@code createdAt}(게스트 발급 시각)이 아니라 {@code registeredAt} 을 본다.
 *
 * <p>출시 시각은 어드민이 {@code carry-over.before-date} · {@code carry-over.before-time} 으로 넣는다(한국 시간).
 * 앱이 스토어에 풀리는 순간에 맞춰야 하는 값이라 배포 없이 바꿀 수 있어야 한다. 안 넣었으면 설정 파일의
 * {@code reward.carry-over-before} 를 쓰고, <b>그것도 없으면 모두 기존 사용자로 본다</b> —
 * 설정을 빠뜨렸을 때 기존 사용자가 이관 선물 대신 환영 선물을 받는 쪽보다, 새 회원이 이관 선물을 받는 쪽이 덜 아프다.
 */
@Slf4j
@Component
public class RewardMemberPolicy {

	private final RewardSettings rewardSettings;
	private final LocalDateTime configuredBefore;

	public RewardMemberPolicy(RewardSettings rewardSettings,
			@Value("${reward.carry-over-before:9999-12-31T00:00:00}") String configuredBefore) {
		this.rewardSettings = rewardSettings;
		this.configuredBefore = LocalDateTime.parse(configuredBefore);
	}

	/**
	 * 어드민이 넣은 날짜와 시각을 출시 시각으로 읽는다.
	 *
	 * @param date yyyyMMdd. 0 이면 안 정한 것이다
	 * @param time HHmm
	 * @return 안 정했으면 비어 있다
	 * @throws DateTimeException 없는 날짜나 시각일 때
	 */
	public static Optional<LocalDateTime> cutoffOf(int date, int time) {
		if (date == 0) {
			return Optional.empty();
		}
		return Optional.of(LocalDateTime.of(
			LocalDate.of(date / 10000, date / 100 % 100, date % 100), LocalTime.of(time / 100, time % 100)));
	}

	/** 지금 쓰이는 출시 시각. 이 시각 전에 가입했으면 기존 사용자다. */
	public LocalDateTime carryOverBefore() {
		int date = rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_DATE);
		int time = rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_TIME);
		try {
			return cutoffOf(date, time).orElse(configuredBefore);
		} catch (DateTimeException exception) {
			log.error("[리워드] 출시 시각 설정을 읽지 못했다 — 설정 파일의 값을 쓴다 [date={}, time={}]", date, time);
			return configuredBefore;
		}
	}

	/** 이관 선물 대상(기존 사용자)인가. 게스트는 아니다. */
	public boolean isCarryOverTarget(Member member) {
		if (member.isGuest()) {
			return false;
		}
		LocalDateTime registeredAt = member.getRegisteredAt() != null
			? member.getRegisteredAt()
			: member.getCreatedAt();
		return registeredAt.isBefore(carryOverBefore());
	}

	/** 환영 선물 대상인가 — 2.0 에서 새로 온, 재가입이 아닌 회원. */
	public boolean isWelcomeTarget(Member member) {
		return !member.isGuest() && !isCarryOverTarget(member) && !member.isRejoined();
	}
}
