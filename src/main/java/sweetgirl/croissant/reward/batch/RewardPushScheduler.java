package sweetgirl.croissant.reward.batch;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.RewardPushService;

/**
 * 리워드의 정기 푸시를 제시간에 부른다.
 *
 * <p><b>기본은 꺼져 있다.</b> 실제 사용자 기기로 나가는 알림이라, 2.0 앱이 나가고 받을 화면이 생긴 뒤에
 * 환경마다 스위치({@code reward.push.*-enabled})를 켠다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardPushScheduler {

	private final RewardPushService rewardPushService;

	@Value("${reward.push.letter-enabled:false}")
	private boolean letterEnabled;

	/** 월요일 오전 10 시 — 이번 주 빵댕이 편지 도착. */
	@Scheduled(cron = "0 0 10 * * MON", zone = "Asia/Seoul")
	public void letterArrivals() {
		if (!letterEnabled) {
			return;
		}
		try {
			rewardPushService.sendLetterArrivals(LocalDateTime.now(CrumbGrantService.ZONE));
		} catch (RuntimeException exception) {
			log.error("[리워드 푸시] 편지 도착 알림 실패 — 이번 주는 보내지 않는다", exception);
		}
	}
}
